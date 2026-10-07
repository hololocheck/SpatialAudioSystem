package com.spatialaudiosystem.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

/**
 * Holds the most recent recording-start refusal for the open Recording screen to display.
 *
 * <p>Client-only: written by {@code RecordingDeviceScreenV2} when the device host's refusal counter moves
 * ({@code network.RecordingDeviceData}), and read by it. One slot is enough — only the screen the player has open
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

    /**
     * Clears the refusal, as {@code requester} asks - only when it is the screen the player has open. The wiki's stand-in
     * of the memory device is drawn inside the wiki and is never that screen, so one whose wiki mode does not answer its
     * buttons itself still leaves the player's refusal showing (second reading, 2026-10-07).
     */
    public static void clear(Screen requester) {
        clear(requester, Minecraft.getInstance().screen);
    }

    /** {@link #clear(Screen)}'s rule, on its parts. */
    static void clear(Object requester, Object open) {
        if (requester != null && requester == open) {
            pos = null;
        }
    }
}
