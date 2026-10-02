package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sends a sound's bytes only to the players who answer that they do not already have them
 * (notes/CLIENT_AUDIO_CACHE.md §2-§3).
 *
 * <p>The play payload carries the audio's content hash. A client that has those bytes in its cache
 * answers {@code have} and plays them at once; one that does not answers {@code need}, and only
 * then is the audio sent. Before this every listener was sent every sound in full, every time --
 * a late listener heard silence for the whole transfer (twelve seconds for 5 MB on a live server,
 * 2026-09-02) even for a sound it had played the day before.
 *
 * <p>Only an answer to an offer this player was made is acted on, and a {@code need} is honoured
 * once: a client cannot make the server send it arbitrary sounds, or one sound over and over. A
 * {@code have} leaves the offer open for one late {@code need}, which is what a client sends when
 * the file it had turns out not to hold those bytes after all.
 */
public final class AudioOffers {

    /**
     * How long an offer waits for its answer. A client answers from its network thread, so a
     * round trip; this is a bound on memory (the offer holds the audio), not a deadline anyone
     * should meet slowly.
     */
    static final long OFFER_TTL_MILLIS = 60_000;

    /**
     * How long an offer outlives a {@code have}. Only a kept file that fails its check sends the
     * late {@code need} this waits for, and that is decided by one read on the client - so the
     * audio (up to ten megabytes, loaded per late listener) is not held for the full minute by
     * every player who did not need it.
     */
    static final long HAVE_GRACE_MILLIS = 10_000;

    /** What an answer did. */
    public enum Outcome { SENT, HIT, IGNORED }

    record Key(UUID player, BlockPos pos, long playbackId) {}

    private static final class Offer {
        final byte[] audio;
        long expiresAt;
        boolean answeredHave;

        Offer(byte[] audio, long expiresAt) {
            this.audio = audio;
            this.expiresAt = expiresAt;
        }
    }

    private static final Map<Key, Offer> OFFERS = new ConcurrentHashMap<>();

    /** The same signal the deliveries write, so one reader sees an offer and its answer together. */
    private static final org.slf4j.Logger SIGNAL = org.slf4j.LoggerFactory.getLogger("SAS-Delivery");

    private AudioOffers() {}

    /** Announces {@code meta} to {@code player}; the audio follows only if the player answers that it needs it. */
    public static void offer(ServerPlayer player, ClientPlayAudioPayload meta, byte[] audio) {
        record(player.getUUID(), meta.pos(), meta.playbackId(), audio, System.currentTimeMillis());
        PacketDistributor.sendToPlayer(player, meta);
    }

    /** A client's answer to an offer. */
    public static void answer(ServerPlayer player, BlockPos pos, long playbackId, boolean have) {
        Outcome outcome = answer(player.getUUID(), pos, playbackId, have, System.currentTimeMillis(),
                audio -> ClientAudioChunkPayload.sendChunked(player, pos, playbackId, audio));
        if (outcome == Outcome.IGNORED) return;
        var profile = player.getGameProfile();
        SIGNAL.info("answered pos={},{},{} id={} to={} cache={}",
                pos.getX(), pos.getY(), pos.getZ(), String.format("%016x", playbackId),
                profile == null ? player.getUUID() : profile.getName(),
                outcome == Outcome.HIT ? "hit" : "miss");
    }

    static void record(UUID player, BlockPos pos, long playbackId, byte[] audio, long now) {
        expire(now);
        OFFERS.put(new Key(player, pos.immutable(), playbackId), new Offer(audio, now + OFFER_TTL_MILLIS));
    }

    static Outcome answer(UUID player, BlockPos pos, long playbackId, boolean have, long now,
                          Consumer<byte[]> send) {
        expire(now);
        Key key = new Key(player, pos.immutable(), playbackId);
        Offer offer = OFFERS.get(key);
        if (offer == null) return Outcome.IGNORED;
        if (have) {
            // A second "have" says nothing the first did not.
            if (offer.answeredHave) return Outcome.IGNORED;
            offer.answeredHave = true;
            offer.expiresAt = Math.min(offer.expiresAt, now + HAVE_GRACE_MILLIS);
            return Outcome.HIT;
        }
        // Removed before sending, so a repeated "need" finds nothing to send.
        OFFERS.remove(key);
        send.accept(offer.audio);
        return Outcome.SENT;
    }

    private static void expire(long now) {
        OFFERS.values().removeIf(o -> now > o.expiresAt);
    }

    static void clearForTest() {
        OFFERS.clear();
    }
}
