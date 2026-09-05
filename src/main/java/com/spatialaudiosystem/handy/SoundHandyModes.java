package com.spatialaudiosystem.handy;

/**
 * The sound handy's wheel arithmetic (spec §2, v2.0): Shift+wheel walks the owner's device
 * list with wrap. There are no tool modes (user decision 2026-09-04, an exception to R3.1.1).
 */
public final class SoundHandyModes {
    private SoundHandyModes() {}

    /**
     * The next device index for a wheel step over {@code n} devices: wraps at both ends, lands
     * on the first (down) or last (up) when nothing is selected, and stays -1 with no devices.
     */
    public static int cycleSelection(int index, int dir, int n) {
        if (n <= 0) return -1;
        if (index < 0 || index >= n) return dir >= 0 ? 0 : n - 1;
        return cycle(index, dir, n);
    }

    private static int cycle(int i, int dir, int n) {
        int d = dir >= 0 ? 1 : -1;
        return ((i + d) % n + n) % n;
    }
}
