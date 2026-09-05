package com.spatialaudiosystem.client;

/**
 * Where the sound handy's panel and mini HUD sit, and whether they can be dragged right now
 * (spec §2.11). Client-only and session-local, the same shape as TSU's transit terminal
 * ({@code TransitTerminalState}): the offsets are a convenience, not saved state, so a restart
 * puts both back at their defaults.
 *
 * <p>The offsets are added to the default corner - the panel is anchored bottom-right, the mini
 * HUD bottom-left - so "reset" is simply zero.
 */
public final class SoundHandyLayoutState {
    private SoundHandyLayoutState() {}

    private static boolean layoutAdjustMode;
    private static int panelOffsetX;
    private static int panelOffsetY;
    private static int hudOffsetX;
    private static int hudOffsetY;

    public static boolean layoutAdjustMode() { return layoutAdjustMode; }

    public static void setLayoutAdjustMode(boolean v) { layoutAdjustMode = v; }

    public static int panelOffsetX() { return panelOffsetX; }

    public static int panelOffsetY() { return panelOffsetY; }

    public static void setPanelOffset(int x, int y) {
        panelOffsetX = x;
        panelOffsetY = y;
    }

    public static int hudOffsetX() { return hudOffsetX; }

    public static int hudOffsetY() { return hudOffsetY; }

    public static void setHudOffset(int x, int y) {
        hudOffsetX = x;
        hudOffsetY = y;
    }

    /** Both back to their default corners. */
    public static void reset() {
        panelOffsetX = 0;
        panelOffsetY = 0;
        hudOffsetX = 0;
        hudOffsetY = 0;
    }
}
