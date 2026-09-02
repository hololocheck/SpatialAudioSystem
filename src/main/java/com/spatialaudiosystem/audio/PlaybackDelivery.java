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

    /** Server ticks are 20 per second by contract, so elapsed ticks convert exactly. */
    private static final int MILLIS_PER_TICK = 50;

    private PlaybackDelivery() {}

    /**
     * Starts a sound at {@code pos} and sends it to the players who should have it.
     *
     * <p><b>Every sound is registered for later delivery, one-shots included.</b> A listener who
     * arrives after it began -- by joining, by returning from another dimension, or by walking
     * into range -- is started at the point the sound has already reached, not at the top.
     *
     * <p>An earlier version registered only endless sounds, on the reasoning that restarting a
     * short one for a late arrival would put them out of step with everyone still hearing the
     * original. That reasoning was right about restarting and wrong about the remedy: the answer
     * is to start them where the sound is, which the offset does, rather than to leave them in
     * silence. Reported from a live server on 2026-08-30 -- a player who joined while a sound was
     * playing heard nothing until it was stopped and started again.
     *
     * <p>A one-shot does not normally stay offerable: the first client to reach its end reports
     * it finished, and that report retires the session for everyone. One case does linger --
     * a sound started with nobody in the level, whose only listener never comes into range.
     * {@link #sweep} returns before sending when nobody is audible, so no client is ever in a
     * position to report it, and for a position with no block entity nothing else ends it. It
     * is one entry per position rather than a leak that grows, but it does read as sounding to
     * anything asking {@code currentId}.
     *
     * <p>The initial send is filtered by audibility for an endless sound and not for a one-shot.
     * For an endless sound the filter is what stops a decode thread, an open audio line and up
     * to ten megabytes being pinned on every player in the dimension for as long as the server
     * runs. For a one-shot it cannot be applied: the schedule advances on a client's report that
     * playback ended, so a one-shot delivered to nobody would leave the device parked forever.
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

        PlaybackSessionRegistry.Replay replay = new PlaybackSessionRegistry.Replay(
                media, format, rangePos1, rangePos2, attenuationMode, attenuationRanges, loop);
        long playbackId = PlaybackSessionRegistry.begin(level, pos, replay);

        // Zero: these players are here for the start, so there is nothing for them to catch up on.
        ClientPlayAudioPayload meta = new ClientPlayAudioPayload(
                pos, playbackId, audioData.length, format, rangePos1, rangePos2,
                attenuationMode, attenuationRanges, loop, 0, true);
        for (ServerPlayer player : level.players()) {
            // Filtered for an endless sound only. A one-shot's completion is reported by a
            // client, and the schedule advances on that report -- so a one-shot that reached
            // nobody never ends, and the device parks with its entry still highlighted. That
            // regression was introduced here on 2026-08-30 by filtering both alike, and it
            // fires whenever nobody is inside the device's range, which for a station device
            // is most of the time. The late-delivery fix does not need this filter: a listener
            // who was not in the level at the start is reached by the sweep either way.
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

        // Where the sound has got to, not where it starts. Clamped to the same bound the
        // decoder enforces, and to that constant rather than to a copy of the number: a value
        // above it is not refused, it throws a DecoderException inside a clientbound payload,
        // which drops the player. Found by review on 2026-08-30, when the writer clamped to
        // Integer.MAX_VALUE and the reader refused anything past seven days -- so a sound that
        // had been running a week would have disconnected everyone who relogged.
        //
        // Clamping does change where an endless sound lands (the residue modulo one pass moves
        // with the clamp), and that is accepted: this design already treats an endless sound's
        // phase as unobservable across listeners, and a listener a week late is not in step
        // with anyone anyway. The client still folds the clamped value modulo one pass, so the
        // cost stays bounded by the audio. An earlier comment here claimed the clamp was
        // phase-preserving; it is not, and it was corrected on 2026-09-02.
        long elapsedTicks = Math.max(0, level.getGameTime() - pending.startedAtGameTime());
        int offsetMillis = (int) Math.min(
                ClientPlayAudioPayload.MAX_START_OFFSET_MILLIS, elapsedTicks * MILLIS_PER_TICK);

        PacketDistributor.sendToPlayer(player, new ClientPlayAudioPayload(
                pending.pos(), pending.playbackId(), audioData.length, replay.format(),
                replay.rangePos1(), replay.rangePos2(),
                replay.attenuationMode(), replay.attenuationRanges(), replay.loop(),
                offsetMillis, true));
        ClientAudioChunkPayload.sendChunked(player, pending.pos(), pending.playbackId(), audioData);
        PlaybackSessionRegistry.markDelivered(
                level.dimension(), pending.pos(), pending.playbackId(), player.getUUID());
        SIGNAL.info("sent {} offset={}", describe(level, pending.pos(), pending.playbackId(),
                replay.loop(), player, replay.rangePos1(), replay.rangePos2(),
                replay.attenuationMode(), replay.attenuationRanges(), "sweep"), offsetMillis);
    }
}
