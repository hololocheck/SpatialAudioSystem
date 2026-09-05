package com.spatialaudiosystem.handy;

/**
 * Remembers the last row signature a device pushed and says when the next one differs. The
 * first signature offered is the baseline and is never a change: a device that just loaded
 * announces itself through its load hook, not through this, so loading N devices at once
 * does not send N lists. Pure, so the rule "first is silent, every later difference pushes"
 * is tested without a level.
 */
public final class RowChangeDetector {
    private String last;

    /** Records {@code signature}; true when it differs from the last one recorded (never on the first). */
    public boolean offer(String signature) {
        String previous = last;
        last = signature;
        return previous != null && !previous.equals(signature);
    }

    /** Whether a signature has been recorded yet. */
    public boolean primed() {
        return last != null;
    }
}
