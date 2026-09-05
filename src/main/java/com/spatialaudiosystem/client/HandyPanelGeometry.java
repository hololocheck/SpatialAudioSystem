package com.spatialaudiosystem.client;

/**
 * Where the handy's panel is on screen. The screen draws a {@code w x h} panel whose unscaled
 * origin is {@code (px, py)}, scaled about its bottom-right corner so it stays flush in that
 * corner; this is that rectangle in GUI coordinates - what the slide is clipped to and what
 * JEI is told to keep clear of while the panel is drawn behind a device screen. Pure, so the
 * pivot arithmetic is tested without a screen.
 */
public final class HandyPanelGeometry {
    private HandyPanelGeometry() {}

    /** {@code {x, y, w, h}} of the scaled panel; at scale 1 it is the unscaled rectangle itself. */
    public static int[] screenRect(int px, int py, int w, int h, float scale) {
        int sw = Math.round(w * scale);
        int sh = Math.round(h * scale);
        return new int[] {px + w - sw, py + h - sh, sw, sh};
    }
}
