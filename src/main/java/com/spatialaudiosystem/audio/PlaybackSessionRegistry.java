package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

import java.security.SecureRandom;
import java.util.Map;
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
 */
public final class PlaybackSessionRegistry {

    /** Not an id any playback is given, so it can mean "nothing playing here". */
    public static final long NO_PLAYBACK = 0L;

    private static final Map<GlobalPos, Long> current = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlaybackSessionRegistry() {}

    /** Issues the id for a sound starting here, displacing whatever was playing before. */
    public static long begin(ServerLevel level, BlockPos pos) {
        long id = newId();
        current.put(GlobalPos.of(level.dimension(), pos), id);
        return id;
    }

    /** The id playing here, or {@link #NO_PLAYBACK}. */
    public static long currentId(ServerLevel level, BlockPos pos) {
        return current.getOrDefault(GlobalPos.of(level.dimension(), pos), NO_PLAYBACK);
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
        return current.remove(GlobalPos.of(level.dimension(), pos), playbackId);
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
