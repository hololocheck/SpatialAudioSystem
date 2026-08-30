package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Getting a sound to the clients that should be playing it — when it starts, and afterwards.
 *
 * <p>Sending only at the moment a sound starts made "playing" a fact about whoever was online
 * at that instant. A player joining, arriving from another dimension, or simply walking into
 * range heard nothing, and because the failure is silence rather than an error it was invisible
 * from every other client.
 *
 * <p>So delivery is a standing question rather than a one-off broadcast: {@link #sweep} asks,
 * a few times a second, which audible players do not have each active sound. That single
 * question covers joining, changing dimension and walking into range together — three separate
 * handlers would each have had to be right on its own, and the one nobody wrote is the one that
 * was missing.
 */
public final class PlaybackDelivery {

    /**
     * Positive signal for what was actually delivered, on its own logger so a driver can read it
     * without parsing the rest of the game's output.
     *
     * <p>Every line carries the position, the playback id, the endless flag, who it went to and
     * the gain that decided it. Those are there so an automated check can be <em>wrong</em> about
     * the result rather than only observing that something happened: "audio was delivered" alone
     * cannot tell the right listener from the wrong one, or an endless sound from a one-shot.
     */
    private static final org.slf4j.Logger SIGNAL =
            org.slf4j.LoggerFactory.getLogger("SAS-Delivery");

    private PlaybackDelivery() {}

    /**
     * Starts a sound at {@code pos} and sends it to the players who should have it.
     *
     * <p><b>Only an endless sound is registered for later delivery.</b> A one-shot behaves as it
     * always has: sent once, to everyone in the level, and never offered again. Two reasons, and
     * both are about one-shots specifically:
     * <ul>
     *   <li>Restarting a short sound from the top for a late arrival puts them out of sync with
     *       everyone still hearing the original. For an endless ambience the phase is not
     *       observable — each listener hears their own client — so the same restart is correct.
     *   <li>A one-shot at a position with no block entity (the public API's case) has nothing
     *       that ever ends its session, so it would stay offerable for the life of the server and
     *       be pushed, long stale, at the first player to walk past.
     * </ul>
     *
     * <p>The initial send is filtered by audibility for an endless sound and not for a one-shot.
     * Again the asymmetry is deliberate: a one-shot has only this one chance to reach a listener,
     * while an endless sound reaches anyone who becomes audible on the next {@link #sweep}. Sending
     * an endless sound to the whole dimension would pin a decode thread, an open audio line and up
     * to ten megabytes on every player, at any distance, for as long as the server runs — there is
     * no distance-based stop on the client, only an explicit one.
     *
     * @return the playback id, or {@link PlaybackSessionRegistry#NO_PLAYBACK} if the audio could
     *         not be loaded, in which case nothing was started or registered
     */
    public static long start(ServerLevel level, BlockPos pos, ItemStack media, String format,
                             BlockPos rangePos1, BlockPos rangePos2,
                             boolean attenuationMode, int[] attenuationRanges, boolean loop) {
        MinecraftServer server = level.getServer();
        if (server == null) return PlaybackSessionRegistry.NO_PLAYBACK;

        byte[] audioData = AudioStorage.loadForItem(server, media);
        if (audioData == null) return PlaybackSessionRegistry.NO_PLAYBACK;

        PlaybackSessionRegistry.Replay replay = loop
                ? new PlaybackSessionRegistry.Replay(
                        media, format, rangePos1, rangePos2, attenuationMode, attenuationRanges, true)
                : null;
        long playbackId = PlaybackSessionRegistry.begin(level, pos, replay);

        ClientPlayAudioPayload meta = new ClientPlayAudioPayload(
                pos, playbackId, audioData.length, format, rangePos1, rangePos2,
                attenuationMode, attenuationRanges, loop);
        for (ServerPlayer player : level.players()) {
            if (loop && !SpatialGain.audible(player.getX(), player.getY(), player.getZ(),
                    pos, rangePos1, rangePos2, attenuationMode, attenuationRanges)) {
                // Logged only here, never in the sweep: a player who stays out of range stays
                // pending, so the sweep would repeat this line every second forever.
                SIGNAL.info("skipped {}", describe(level, pos, playbackId, loop, player,
                        rangePos1, rangePos2, attenuationMode, attenuationRanges, "start"));
                continue;
            }
            PacketDistributor.sendToPlayer(player, meta);
            ClientAudioChunkPayload.sendChunked(player, pos, playbackId, audioData);
            PlaybackSessionRegistry.markDelivered(level.dimension(), pos, playbackId, player.getUUID());
            SIGNAL.info("sent {}", describe(level, pos, playbackId, loop, player,
                    rangePos1, rangePos2, attenuationMode, attenuationRanges, "start"));
        }
        return playbackId;
    }

    /**
     * One delivery decision, in a form a reader can be wrong about.
     *
     * <p>The gain is recomputed rather than threaded out of the decision so that
     * {@link SpatialGain#audible} stays the single gate; it is pure arithmetic and this runs once
     * per player per sound, not per tick.
     */
    private static String describe(ServerLevel level, BlockPos pos, long playbackId, boolean loop,
                                   ServerPlayer player, BlockPos rangePos1, BlockPos rangePos2,
                                   boolean attenuationMode, int[] ranges, String via) {
        float gain = SpatialGain.linearGain(player.getX(), player.getY(), player.getZ(),
                pos, rangePos1, rangePos2, attenuationMode, ranges);
        // A line that observes delivery must not be able to stop it. The profile is never null on
        // a real ServerPlayer, which is exactly why an unguarded read here would only ever fail
        // somewhere this signal was supposed to be watching.
        var profile = player.getGameProfile();
        String name = profile == null ? player.getUUID().toString() : profile.getName();
        return String.format("pos=%d,%d,%d dim=%s id=%016x loop=%s to=%s gain=%.3f via=%s",
                pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().location(), playbackId, loop,
                name, gain, via);
    }

    /**
     * Sends every active sound to every player who can hear it and has not been sent it.
     *
     * <p>Called on a timer rather than from join / dimension-change events, because the set of
     * players who can hear a sound also changes when nobody fires an event at all.
     */
    public static void sweep(MinecraftServer server) {
        if (PlaybackSessionRegistry.isEmpty()) return;

        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;

            for (ServerPlayer player : players) {
                for (PlaybackSessionRegistry.Pending pending
                        : PlaybackSessionRegistry.pendingFor(level.dimension(), player.getUUID())) {
                    deliver(level, player, pending);
                }
            }
        }
    }

    /** Sends one active sound to one player, if that player is somewhere it can be heard. */
    private static void deliver(ServerLevel level, ServerPlayer player,
                                PlaybackSessionRegistry.Pending pending) {
        PlaybackSessionRegistry.Replay replay = pending.replay();

        // The same predicate the client uses to set the gain. Asking it here is what keeps a
        // ten-megabyte transfer from going to someone who would hear silence.
        if (!SpatialGain.audible(player.getX(), player.getY(), player.getZ(),
                pending.pos(), replay.rangePos1(), replay.rangePos2(),
                replay.attenuationMode(), replay.attenuationRanges())) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server == null) return;
        byte[] audioData = AudioStorage.loadForItem(server, replay.media());
        if (audioData == null) {
            // The medium's audio is gone from storage. Marking the player as delivered stops
            // this from being retried every sweep for as long as the sound stays active.
            PlaybackSessionRegistry.markDelivered(
                    level.dimension(), pending.pos(), pending.playbackId(), player.getUUID());
            SpatialAudioSystem.LOGGER.warn("No audio data for the sound at {}; not sending it to {}",
                    pending.pos(), player.getGameProfile().getName());
            return;
        }

        PacketDistributor.sendToPlayer(player, new ClientPlayAudioPayload(
                pending.pos(), pending.playbackId(), audioData.length, replay.format(),
                replay.rangePos1(), replay.rangePos2(),
                replay.attenuationMode(), replay.attenuationRanges(), replay.loop()));
        ClientAudioChunkPayload.sendChunked(player, pending.pos(), pending.playbackId(), audioData);
        PlaybackSessionRegistry.markDelivered(
                level.dimension(), pending.pos(), pending.playbackId(), player.getUUID());
        SIGNAL.info("sent {}", describe(level, pending.pos(), pending.playbackId(), replay.loop(),
                player, replay.rangePos1(), replay.rangePos2(),
                replay.attenuationMode(), replay.attenuationRanges(), "sweep"));
    }
}
