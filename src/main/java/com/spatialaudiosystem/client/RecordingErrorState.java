package com.spatialaudiosystem.client;

import net.minecraft.core.BlockPos;

/**
 * Holds the most recent recording-start refusal for the open Recording screen to display.
 *
 * <p>Client-only: written from the {@code RecordingErrorPayload} handler and read by
 * {@code RecordingDeviceScreenV2}. One slot is enough — only the screen the player has open
 * can produce a refusal, and it clears itself after a few seconds.
 */
public final class RecordingErrorState {

    private static final long SHOW_MS = 4000L;

    private static BlockPos pos;
    private static int reason;
    private static long expiryMs;

    private RecordingErrorState() {}

    public static void set(BlockPos p, int reasonCode) {
        pos = p;
        reason = reasonCode;
        expiryMs = System.currentTimeMillis() + SHOW_MS;
    }

    /** The active refusal reason for {@code p}, or {@code -1} if none is showing. */
    public static int reasonFor(BlockPos p) {
        if (pos != null && pos.equals(p) && System.currentTimeMillis() < expiryMs) {
            return reason;
        }
        return -1;
    }

    public static void clear() {
        pos = null;
    }
}
