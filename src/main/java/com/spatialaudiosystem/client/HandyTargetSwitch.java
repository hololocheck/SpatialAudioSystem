package com.spatialaudiosystem.client;

/**
 * The mini HUD's target switch (user's real-device note 2026-09-05): when Shift+wheel moves the
 * handy to another device, the panel's rows slide out and the new device's rows slide in, the
 * way the wheel went, so the change is seen and not only read.
 *
 * <p>Pure state, fed the target's list index once per frame. A change from one device to
 * another starts a slide; the first frame, a lost target and a newly found one do not (nothing
 * to slide from or to). The direction is the wheel's when it said so just before (a wrap from
 * the last device to the first still reads as "next"); otherwise the index order decides.
 * Progress is ease-out cubic (BELUGAEXPERIENCE R2.7.1) over {@link #SLIDE_NANOS}.
 */
public final class HandyTargetSwitch {
    public static final long SLIDE_NANOS = 220_000_000L;
    /** How long a wheel's direction hint waits for the frame that shows the change. */
    static final long HINT_NANOS = 500_000_000L;

    /** -1 until a target has been shown: the first frame has nothing to slide from. */
    private int shownIndex = -1;
    private int direction;
    private long startedAt;
    private int hintedDir;
    private long hintedAt;

    /** A wheel step went this way ({@code +1} = the next device in the list); the slide it causes follows it. */
    public void hint(int dir, long now) {
        hintedDir = Integer.signum(dir);
        hintedAt = now;
    }

    /**
     * The target index shown this frame ({@code -1} = none). Returns true when a slide starts,
     * i.e. the index moved from one device to another.
     */
    public boolean offer(int index, long now) {
        if (index == shownIndex) return false;
        int from = shownIndex;
        shownIndex = index;
        if (from < 0 || index < 0) {
            startedAt = 0;
            return false;
        }
        boolean hinted = hintedDir != 0 && now - hintedAt >= 0 && now - hintedAt <= HINT_NANOS;
        direction = hinted ? hintedDir : (index > from ? 1 : -1);
        hintedDir = 0;
        startedAt = now;
        return true;
    }

    /** {@code +1}: the rows move up (the new target came from below), {@code -1}: down. */
    public int direction() {
        return direction;
    }

    /** Eased 0..1 progress of the running slide; 1 when none runs. */
    public float progress(long now) {
        if (startedAt == 0) return 1f;
        long elapsed = now - startedAt;
        if (elapsed >= SLIDE_NANOS) return 1f;
        float t = Math.max(0f, elapsed / (float) SLIDE_NANOS);
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }
}
