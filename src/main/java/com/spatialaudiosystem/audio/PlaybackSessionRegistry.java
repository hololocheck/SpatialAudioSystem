package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which sound is currently playing at each position, per dimension.
 *
 * <p>A {@code BlockPos} alone cannot identify a playback. The same position exists in
 * every dimension, and the same device can stop and restart faster than the round trip
 * to a client, so a report arriving late says nothing about which sound it refers to.
 * Each start is given an id that travels with the audio and comes back on the report.
 *
 * <p>Ids are random rather than sequential so a client cannot name a sound it was never
 * told about.
 *
 * <p>A session also carries what is needed to start the same sound on one client that does
 * not have it, and the set of players who have been sent it. Without that, "playing" was
 * only ever a fact about the players who happened to be online at the instant it started:
 * anyone joining later, or arriving from another dimension, heard nothing and no error was
 * raised anywhere. The registry is the only thing that knows a sound is still going, so it
 * is what has to be able to answer "who still needs this".
 */
public final class PlaybackSessionRegistry {

    /** Not an id any playback is given, so it can mean "nothing playing here". */
    public static final long NO_PLAYBACK = 0L;

    /**
     * Everything required to start a sound again for a single listener.
     *
     * <p>The medium is held rather than the decoded bytes: audio is up to ten megabytes and a
     * looping ambience stays active for the life of the server, so keeping the bytes resident
     * per device would cost more than re-reading them at the rare moment someone arrives.
     */
    public record Replay(ItemStack media, String format,
                         BlockPos rangePos1, BlockPos rangePos2,
                         boolean attenuationMode, int[] attenuationRanges,
                         boolean loop) {
        public Replay {
            // Defensive copies: the caller's stack goes on being edited in a container slot,
            // and the ranges array is reused across calls.
            media = media.copy();
            attenuationRanges = attenuationRanges.clone();
        }
    }

    /** One active sound: its identity, how to replay it, when it started, and who has it. */
    private static final class Session {
        final long id;
        final Replay replay;                        // null: started by a path that cannot replay
        /**
         * Server game time at which the sound began.
         *
         * <p>Held so a listener who arrives later can be started at the point the sound has
         * already reached, rather than at the top. Starting a late arrival from the top is what
         * the delivery sweep did for its first version, and it is wrong twice over: a one-shot
         * puts them out of step with everyone still hearing the original, and a sound that
         * finished before they arrived begins again for them alone.
         */
        final long startedAtGameTime;
        final Set<UUID> delivered = ConcurrentHashMap.newKeySet();
        /** Players whose client has reported what it did with the offset. One report each. */
        final Set<UUID> reported = ConcurrentHashMap.newKeySet();

        Session(long id, Replay replay, long startedAtGameTime) {
            this.id = id;
            this.replay = replay;
            this.startedAtGameTime = startedAtGameTime;
        }
    }

    /** An active sound a particular player has not been sent yet. */
    public record Pending(BlockPos pos, long playbackId, Replay replay, long startedAtGameTime) {}

    private static final Map<GlobalPos, Session> current = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlaybackSessionRegistry() {}

    /** Issues the id for a sound starting here, displacing whatever was playing before. */
    public static long begin(ServerLevel level, BlockPos pos) {
        return begin(level, pos, null);
    }

    /**
     * Issues the id for a sound starting here, remembering how to start it for a listener who
     * arrives later. A null {@code replay} registers the sound without that ability.
     */
    public static long begin(ServerLevel level, BlockPos pos, Replay replay) {
        long id = newId();
        current.put(GlobalPos.of(level.dimension(), pos),
                new Session(id, replay, level.getGameTime()));
        return id;
    }

    /**
     * Accepts a client's catch-up report, once per player per sound.
     *
     * <p>The report reaches only a log line, but that line exists so the server's log can be
     * trusted about what a client did -- so it must not be writable by a client that was never
     * sent the sound, or writable a thousand times by one that was. The id is server-issued
     * and random, so naming a current one is proof of having been sent it; the delivered set
     * is the second check for a player who learned the id some other way.
     */
    public static boolean acceptCatchUpReport(ServerLevel level, BlockPos pos, long playbackId,
                                              UUID playerId) {
        Session s = current.get(GlobalPos.of(level.dimension(), pos));
        if (s == null || s.id != playbackId || !s.delivered.contains(playerId)) return false;
        return s.reported.add(playerId);
    }

    /** The id playing here, or {@link #NO_PLAYBACK}. */
    public static long currentId(ServerLevel level, BlockPos pos) {
        Session s = current.get(GlobalPos.of(level.dimension(), pos));
        return s == null ? NO_PLAYBACK : s.id;
    }

    /** Forgets the sound here, whichever it is. */
    public static void end(ServerLevel level, BlockPos pos) {
        current.remove(GlobalPos.of(level.dimension(), pos));
    }

    /**
     * Accepts an end-of-playback report for {@code playbackId}, once.
     *
     * <p>Returns false for a report about a sound that has already ended, has been
     * replaced, or was never started here. The removal is atomic, so among several
     * clients reporting the same sound exactly one is accepted.
     */
    public static boolean consumeIfCurrent(ServerLevel level, BlockPos pos, long playbackId) {
        if (playbackId == NO_PLAYBACK) return false;
        boolean[] consumed = {false};
        current.computeIfPresent(GlobalPos.of(level.dimension(), pos), (key, session) -> {
            if (session.id != playbackId) return session;
            consumed[0] = true;
            return null;   // returning null removes the entry, atomically with the check
        });
        return consumed[0];
    }

    /**
     * Active replayable sounds in {@code dim} that {@code playerId} has not been sent.
     *
     * <p>Answering "has not been sent" from the registry rather than from a join event is what
     * makes one sweep cover joining, changing dimension and walking into range alike.
     */
    public static List<Pending> pendingFor(ResourceKey<Level> dim, UUID playerId) {
        List<Pending> pending = new ArrayList<>();
        for (Map.Entry<GlobalPos, Session> e : current.entrySet()) {
            if (!e.getKey().dimension().equals(dim)) continue;
            Session s = e.getValue();
            if (s.replay == null || s.delivered.contains(playerId)) continue;
            pending.add(new Pending(e.getKey().pos(), s.id, s.replay, s.startedAtGameTime));
        }
        return pending;
    }

    /**
     * Records that {@code playerId} has been sent this sound, so a sweep does not send it twice.
     *
     * <p>Ignored if the sound here has since been replaced — marking against the new sound's
     * id would suppress the delivery that sound still needs.
     */
    public static void markDelivered(ResourceKey<Level> dim, BlockPos pos, long playbackId, UUID playerId) {
        Session s = current.get(GlobalPos.of(dim, pos));
        if (s != null && s.id == playbackId) s.delivered.add(playerId);
    }

    /**
     * Drops every delivery record for a player, so the next sweep sends them what they can hear.
     *
     * <p>Called when a player logs out or changes dimension: both unload the client level, and
     * the client stops and forgets its sounds when that happens. A player whose client no longer
     * holds the audio but who is still recorded as having been sent it would never hear it again.
     */
    public static void forgetPlayer(UUID playerId) {
        for (Session s : current.values()) {
            s.delivered.remove(playerId);
            // The next delivery is a new one to this client, and its report is the one that
            // matters -- it carries the non-zero offset. Keeping the old mark refused exactly
            // that report (review, 2026-09-02).
            s.reported.remove(playerId);
        }
    }

    /** True when nothing is playing anywhere, so a sweep can return without allocating. */
    public static boolean isEmpty() {
        return current.isEmpty();
    }

    /** Drops all state. The registry is per-run, so a new world must not inherit ids. */
    public static void clear() {
        current.clear();
    }

    private static long newId() {
        long id;
        do {
            id = RANDOM.nextLong();
        } while (id == NO_PLAYBACK);
        return id;
    }
}
