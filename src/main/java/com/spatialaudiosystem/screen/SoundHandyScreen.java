package com.spatialaudiosystem.screen;

import com.manta.api.controller.ScrollViewport;
import com.manta.api.controller.TextInputController;
import com.manta.api.controller.ToggleSwitchController;
import com.manta.api.hud.HudCoexistentScreen;
import com.manta.api.render.TextCaretRenderer;
import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.BoolSlot;
import com.manta.api.state.ColorSlot;
import com.manta.api.state.MantaState;
import com.manta.api.state.NumberSlot;
import com.manta.api.state.TextSlot;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.client.HandyClient;
import com.spatialaudiosystem.client.HandyDeviceListClient;
import com.spatialaudiosystem.client.SoundHandyLayoutState;
import com.spatialaudiosystem.handy.HandyActions;
import com.spatialaudiosystem.handy.HandyDeviceRow;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.SoundHandyItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The sound handy's screen (spec §2.4, v2.0): a phone-sized panel anchored bottom-right, the
 * way TSU's transit terminal is, that slides up from the bottom edge when opened and back down
 * when closed. Two pages under the nav - the owner's devices as a list, and the handy's
 * settings - and a third that is not a tab: clicking a device in the list opens that device's
 * page (name, position, state, and the actions), with a back arrow to the list.
 *
 * <p>Nothing here is authoritative. The rows are the server-synced cache
 * ({@link HandyDeviceListClient}); the target and the HUD flag live on the handy as data
 * components, so the wheel, the mini HUD and this screen all read one value. A click echoes
 * the change on the client stack for immediate feedback and sends the action; the server's
 * stack sync then confirms or corrects it.
 */
public class SoundHandyScreen extends JsonLayoutPlainScreen implements HudCoexistentScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }
    static final int PANEL_W = 220;
    static final int PANEL_H = 360;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 12;
    /** The transit terminal's slide (220 ms, ease-out), so the two panels feel like one family. */
    private static final long SLIDE_NANOS = 220_000_000L;

    /** Rows the list shows at once; the layout's repeat height is this many strides. */
    static final int VISIBLE_ROWS = 10;
    private static final int LIST_Y = 30;
    private static final int ROW_STRIDE = 26;
    private static final int LIST_H = VISIBLE_ROWS * ROW_STRIDE;
    private static final int THUMB_H = 30;
    private static final int ROW_ICON_X = 10;
    private static final int ROW_ICON_DY = 4;

    /** The three pages. R4.20.3: an enum stays an enum -- do not flatten it back to an int. */
    private enum Page { LIST, DEVICE, SETTINGS }
    private static final int SELECTED_BG = 0x334FC3F7;
    private static final int SELECTED_BORDER = 0xFF4FC3F7;
    private static final int NAV_ACTIVE_BG = 0x2E4FC3F7;
    private static final int DOT_PLAYING = 0xFF66BB6A;
    private static final int DOT_NO_MEDIUM = 0xFFEF5350;
    private static final int DOT_UNLOADED = 0xFF444444;
    // The icon is resolved on first use by the HUD renderer: a static initializer calling a
    // registry holder is what broke mod loading on 2026-09-04 (see SoundHandyHudRenderer).

    /** The layout-adjust drag: nothing, the mini HUD, or the panel by its header. */
    private static final int DRAG_NONE = 0;
    private static final int DRAG_HUD = 1;
    private static final int DRAG_PANEL = 2;
    private static final int HEADER_H = 24;
    private static final int ADJUST_ACCENT = 0xFFFFD54F;

    /** The device page slides in from the left and back out; the list stays put. */
    private static final long DEV_SLIDE_NANOS = 220_000_000L;
    /**
     * The dialog frame's cyan border (Manta's {@code DialogFrame.DIALOG_BORDER_W}, which is not
     * on the API surface): the slide is clipped inside it, so the page appears out of the navy
     * area and never over the border.
     */
    private static final int FRAME_BORDER_W = 2;
    /** The frame's corner radius and border colour (Manta's {@code DialogFrame}: 10 px, #4fc3f7), for the redraw during the slide. */
    private static final int FRAME_RADIUS = 10;
    private static final int FRAME_BORDER_ARGB = 0xFF4FC3F7;
    /**
     * The handy screen that "open device" was pressed on: the device screen draws it behind
     * itself and hands the player back to it on close, so the handy is never "closed" by
     * opening a device (user's real-device note 2026-09-05). Claimed once by the device screen.
     */
    private static SoundHandyScreen behind;

    private final com.manta.api.controller.TabController<Page> pages =
            new com.manta.api.controller.TabController<>(
                    java.util.List.of(Page.LIST, Page.DEVICE, Page.SETTINGS), Page.LIST);
    /** Where a finished slide-out should land; null while nothing is pending. */
    private Page pendingPage;
    private long devSlideNano;
    private boolean devSlidingOut;
    private boolean listRequested;
    private int dragging = DRAG_NONE;
    private int dragFromX;
    private int dragFromY;
    // Bottom-right anchor, refreshed every frame through dialogAnchor (a resize moves it).
    private int px;
    private int py;
    // The slide: open = from below the bottom edge to resting; close = resting to below.
    private final long openedAtNano = System.nanoTime();
    private boolean closing;
    private long closingAtNano;

    private final ScrollViewport listScroll =
            new ScrollViewport(() -> HandyDeviceListClient.rows().size(), VISIBLE_ROWS);
    private final TextInputController nameInput =
            new TextInputController(SoundDeviceRegistry.MAX_NAME_CODE_POINTS, "")
                    .onSubmit(this::submitName)
                    .onEscape(this::cancelName);
    private final ToggleSwitchController hudToggle = new ToggleSwitchController("hd-hud-track", "hd-hud-knob",
            () -> !handy().getOrDefault(ModDataComponents.HANDY_HUD_HIDDEN, false), this::setHudShown);
    private final ToggleSwitchController layoutToggle = new ToggleSwitchController("hd-layout-track", "hd-layout-knob",
            SoundHandyLayoutState::layoutAdjustMode, SoundHandyLayoutState::setLayoutAdjustMode);

    // ===== Manta 7 push (Phase 4, 2026-09-23) =====
    // Every value of the page is WRITTEN here each frame before the engine draws (render and
    // renderBehind): the device page's slide moves on the clock and the name box follows every
    // key, so a frame is the cadence the pull had - an unchanged value costs nothing. The screen
    // overrides no getDynamic*. The boxes are pushed as OFFSETS from each node's own static value
    // (the pull answered default + delta; hd-dev-x moves nodes that sit at three different x), a
    // list row that the pull answered with its default is pushed "none" (clear), and the hint
    // toggle and the transitions are the base screen's (FrameworkState).
    private MantaState pushed;
    private TextSlot tRowName, tRowPos, tDevName, tDevPos, tDevStatus;
    private ColorSlot cRowBg, cRowBorder, cRowDot, cNavList, cNavSettings,
            cHudTrack, cHudKnob, cLayoutTrack, cLayoutKnob, cDevNameBorder;
    private NumberSlot nCount, nThumbY, nHudKnobX, nLayoutKnobX, nDevX;
    private BoolSlot bTabList, bTabDevice, bTabSettings, bScrollbar, bListEmpty;

    public SoundHandyScreen(ItemStack handy) {
        super(Component.translatable("gui.spatialaudiosystem.sound_handy.title"));
        // Always the list: the mini HUD already names the target, and opening straight onto its
        // page read as "the handy jumps somewhere" (user's real-device note 2026-09-05).
        this.pages.setCurrent(Page.LIST);
    }

    @Override
    protected String layoutJson() {
        return SasLayouts.load("layouts/sound-handy.json");
    }

    @Override
    protected String wikiPageId() { return "tools/sound-handy"; }

    // ---- placement: bottom-right, phone-sized, slid in from below (the transit terminal's shape) ----

    @Override
    protected int[] dialogAnchor(int displayW, int displayH) {
        px = this.width - PANEL_W - RIGHT_MARGIN + SoundHandyLayoutState.panelOffsetX();
        py = this.height - PANEL_H - BOTTOM_MARGIN + SoundHandyLayoutState.panelOffsetY();
        return new int[] {px, py};
    }

    /**
     * The world stays visible behind the panel, as it does behind TSU's transit terminal: the
     * base already suppresses the blur and the transparent gradient, but vanilla's
     * {@code renderBackground} still dims an in-world screen, so it is skipped whole
     * (user's real-device note 2026-09-05).
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Nothing: this is a small corner panel, not a full-screen dialog.
    }

    /** A fixed 220x360 panel: no auto-scale, so px/py and the dialog origin stay 1:1. */
    @Override
    protected boolean autoScaleEnabled() { return false; }

    @Override
    protected void init() {
        super.init();   // parses the layout and places the dialog from dialogAnchor
        // The list is pushed on every change, but a fresh copy at open costs one packet and
        // covers a handy picked up before the client had the list (init re-runs on resize).
        if (!listRequested) {
            listRequested = true;
            HandyClient.action(HandyActions.REQUEST_LIST, 0, null);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        float offY = slideOffsetY();
        float s = panelScale();
        int pvx = px + PANEL_W;
        int pvy = py + PANEL_H;   // bottom-right pivot keeps the panel flush in the corner
        // The device page enters and leaves through the panel's left edge: while it moves, the
        // nodes past that edge must not show outside the panel (user's real-device note
        // 2026-09-05). GuiGraphics' scissor is in GUI coordinates, not the pose's, so the
        // clip is the panel's on-screen rectangle, shifted by the open/close slide.
        boolean clip = devPageOffset() != 0f;
        if (clip) {
            // Inside the frame's cyan border, not the panel's outer edge: the page must come
            // out of the navy area itself (user's real-device note 2026-09-05, round 5).
            int[] r = screenRect();
            int dy = Math.round(offY);
            int inset = Math.round(FRAME_BORDER_W * s);
            g.enableScissor(r[0] + inset, r[1] + dy + inset, r[0] + r[2] - inset, r[1] + r[3] + dy - inset);
        }
        g.pose().pushPose();
        if (offY != 0f) g.pose().translate(0, offY, 0);
        if (s != 1f) {
            g.pose().translate(pvx, pvy, 0);
            g.pose().scale(s, s, 1f);
            g.pose().translate(-pvx, -pvy, 0);
        }
        super.render(g, (int) Math.round(sMx(mouseX)), (int) Math.round(sMy(mouseY)), partialTick);
        if (clip) {
            // The clip took the frame's own border with it (the engine paints the frame before
            // the nodes, so the border cannot be left out of the clip - real-device note
            // 2026-09-05, round 6). Draw the border again, unclipped, under the same pose: the
            // same stroke the frame uses (an inside band), so it lands on the same pixels.
            g.disableScissor();
            com.manta.api.draw.SmoothRenderer.strokeRoundedRect(g, px, py, PANEL_W, PANEL_H,
                    FRAME_RADIUS, FRAME_BORDER_W, FRAME_BORDER_ARGB);
        }
        g.pose().popPose();
        settleDevPage();
        if (closing && closeProgress() >= 1f) finishClose();
    }

    /**
     * The panel's on-screen rectangle {@code {x, y, w, h}} at rest: the dialog origin, scaled
     * about the bottom-right pivot the way {@link #render} draws it. What the device screen
     * reports to JEI while this panel is drawn behind it, and what the slide clips to.
     */
    int[] screenRect() {
        return com.spatialaudiosystem.client.HandyPanelGeometry.screenRect(px, py, PANEL_W, PANEL_H, panelScale());
    }

    /**
     * The same frame, drawn by the device screen that was opened from this one: the panel at
     * rest, no slide, no hover. Input never reaches it while it is behind.
     */
    void renderBehind(GuiGraphics g, float partialTick) {
        pushAll();
        float s = panelScale();
        int pvx = px + PANEL_W;
        int pvy = py + PANEL_H;
        g.pose().pushPose();
        if (s != 1f) {
            g.pose().translate(pvx, pvy, 0);
            g.pose().scale(s, s, 1f);
            g.pose().translate(-pvx, -pvy, 0);
        }
        super.render(g, -1, -1, partialTick);
        g.pose().popPose();
    }

    /**
     * Follows the GUI scale (R4.22.4), shrinking only when the panel would not fit.
     *
     * <p>This used to multiply by {@code 2.0 / guiScale} -- the "always GUI scale 2"
     * normalisation the rules banned in v1.22. Identical at GUI scale 2; at other scales the
     * panel now follows the setting instead of cancelling it.
     */
    private float panelScale() {
        return com.manta.api.screen.PanelFit.scale(this.width, this.height, PANEL_W, PANEL_H, 4);
    }

    /** Screen mouse -> the panel's own coordinates (bottom-right pivot), where every hit-test lives. */
    private double sMx(double mx) { float s = panelScale(); int p = px + PANEL_W; return p + (mx - p) / s; }
    private double sMy(double my) { float s = panelScale(); int p = py + PANEL_H; return p + (my - p) / s; }

    private float slideOffsetY() {
        if (JsonLayoutScreen.WIKI_CAPTURE_MODE) return 0f;   // the capture wants the resting frame
        float dist = this.height - getDialogScreenY();
        if (closing) return dist * easeOut(closeProgress());
        long elapsed = System.nanoTime() - openedAtNano;
        if (elapsed >= SLIDE_NANOS) return 0f;
        float t = elapsed / (float) SLIDE_NANOS;
        return dist * (1f - easeOut(t));
    }

    private float closeProgress() {
        if (!closing) return 0f;
        return Math.min(1f, (System.nanoTime() - closingAtNano) / (float) SLIDE_NANOS);
    }

    /** パネルのスライド。 二次 ease-out のまま (動きは変えず式だけ Manta の {@code Easing} へ)。 */
    private static float easeOut(float t) {
        return com.manta.api.anim.Easing.EASE_OUT.apply(t);
    }

    /** Close = slide down, then the real close; the base's scale+fade does not run (onClose is ours). */
    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        closingAtNano = System.nanoTime();
    }

    private void finishClose() {
        closing = false;
        super.performClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The slide moves what is drawn but not the hit-test (sMy undoes the scale only), so a
        // click during either slide would land on the wrong element: swallow it (review 2026-09-04).
        if (closing || slideOffsetY() != 0f) return true;
        // Same for the device page's own slide: a button in motion must not act (review 2026-09-05).
        if (devPageOffset() != 0f) return true;
        if (SoundHandyLayoutState.layoutAdjustMode() && button == 0 && beginDrag(mouseX, mouseY)) return true;
        return super.mouseClicked(sMx(mouseX), sMy(mouseY), button);
    }

    /** Grabs the mini HUD or the panel header, in raw screen coordinates. */
    private boolean beginDrag(double mouseX, double mouseY) {
        int hudX = SoundHandyHudRenderer.restingX();
        int hudY = SoundHandyHudRenderer.restingY(this.height);
        if (inRect(mouseX, mouseY, hudX, hudY, SoundHandyHudRenderer.PANEL_W, SoundHandyHudRenderer.PANEL_H)) {
            dragging = DRAG_HUD;
            dragFromX = (int) mouseX - SoundHandyLayoutState.hudOffsetX();
            dragFromY = (int) mouseY - SoundHandyLayoutState.hudOffsetY();
            return true;
        }
        int headerX = dialogLocalToScreenX(0);
        int headerY = dialogLocalToScreenY(0);
        if (inRect(mouseX, mouseY, headerX, headerY, dialogScaleAmount(PANEL_W), dialogScaleAmount(HEADER_H))) {
            dragging = DRAG_PANEL;
            dragFromX = (int) mouseX - SoundHandyLayoutState.panelOffsetX();
            dragFromY = (int) mouseY - SoundHandyLayoutState.panelOffsetY();
            return true;
        }
        return false;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return com.manta.api.hud.Rects.contains(mx, my, x, y, w, h);
    }

    /** The blinking caret over the name box while it is being edited (the shared component). */
    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        if ("hd-dev-name-caret".equals(key) && nameInput.isFocused()) {
            // The typed text, not display(): that one shows the placeholder while the box is
            // empty, and the caret would sit after the placeholder instead of at the start.
            TextCaretRenderer.draw(g, this.font, nameInput.value(), x, y, w, h, SELECTED_BORDER, nameInput);
        }
    }

    /**
     * The handy screen a device screen should draw behind itself and return to - but only when
     * that handy is {@code current}, the screen being replaced right now. A refused OPEN leaves
     * the handoff behind; without this check the next device opened from the world would adopt
     * it (review 2026-09-05). Claims it.
     */
    static SoundHandyScreen takeBehindIf(net.minecraft.client.gui.screens.Screen current) {
        SoundHandyScreen s = behind;
        if (s == null || s != current) return null;
        behind = null;
        return s;
    }

    /** Leaving for any other reason than a device screen taking over drops the handoff. */
    @Override
    public void removed() {
        super.removed();
        if (behind == this) behind = null;
    }

    /**
     * An offset that keeps a {@code size}-wide box, whose unshifted edge is {@code base}, inside
     * a {@code screen}-wide screen. A box dragged past an edge could not be grabbed again.
     */
    private static int clampOffset(int offset, int base, int size, int screen) {
        int min = -base;                       // flush against the left / top edge
        int max = screen - size - base;        // flush against the right / bottom edge
        if (max < min) return 0;               // a screen smaller than the box: leave it at rest
        return Math.max(min, Math.min(max, offset));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != DRAG_NONE) {
            dragging = DRAG_NONE;
            return true;
        }
        return super.mouseReleased(sMx(mouseX), sMy(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging == DRAG_HUD) {
            int ox = (int) mouseX - dragFromX;
            int oy = (int) mouseY - dragFromY;
            // Clamped so neither panel can be dragged off the screen and become unreachable
            // (there would be no way to grab it again - review 2026-09-05).
            SoundHandyLayoutState.setHudOffset(
                    clampOffset(ox, SoundHandyHudRenderer.restingX() - SoundHandyLayoutState.hudOffsetX(),
                            SoundHandyHudRenderer.PANEL_W, this.width),
                    clampOffset(oy, SoundHandyHudRenderer.restingY(this.height) - SoundHandyLayoutState.hudOffsetY(),
                            SoundHandyHudRenderer.PANEL_H, this.height));
            return true;
        }
        if (dragging == DRAG_PANEL) {
            int ox = (int) mouseX - dragFromX;
            int oy = (int) mouseY - dragFromY;
            SoundHandyLayoutState.setPanelOffset(
                    clampOffset(ox, this.width - PANEL_W - RIGHT_MARGIN, PANEL_W, this.width),
                    clampOffset(oy, this.height - PANEL_H - BOTTOM_MARGIN, PANEL_H, this.height));
            return true;
        }
        return super.mouseDragged(sMx(mouseX), sMy(mouseY), button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) return true;
        return super.mouseScrolled(sMx(mouseX), sMy(mouseY), scrollX, scrollY);
    }

    /** The playback device's own item icon at the head of each visible row (the layout has no item node). */
    @Override
    protected void afterDialogRender(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (pages.is(Page.LIST)) {
            int rows = listScroll.rowCount();
            for (int i = 0; i < rows; i++) {
                int x = dialogLocalToScreenX(ROW_ICON_X);
                int y = dialogLocalToScreenY(LIST_Y + i * ROW_STRIDE + ROW_ICON_DY);
                g.renderItem(SoundHandyHudRenderer.deviceIcon(), x, y);
            }
        }
        if (SoundHandyLayoutState.layoutAdjustMode()) renderLayoutAdjust(g);
    }

    /**
     * Layout-adjust mode: the mini HUD is drawn where it will actually appear, so it can be
     * dragged while this screen is open (the HUD itself never renders over a screen, R2.3.2).
     * The panel is dragged by its own header. Both are outlined so what will move is visible.
     */
    private void renderLayoutAdjust(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        // The mini HUD itself is drawn by its renderer under this screen (HudCoexistentScreen);
        // here only the frame that says "this one moves", at its resting place.
        int hudX = SoundHandyHudRenderer.restingX();
        int hudY = SoundHandyHudRenderer.restingY(this.height);
        outline(g, hudX - 1, hudY - 1, SoundHandyHudRenderer.PANEL_W + 2, SoundHandyHudRenderer.PANEL_H + 2);
        com.manta.api.text.MantaText.draw(g, mc.font, Component.translatable("gui.spatialaudiosystem.sound_handy.drag_hud").getString(), hudX, hudY - 11, ADJUST_ACCENT);
        int headerX = dialogLocalToScreenX(0);
        int headerY = dialogLocalToScreenY(0);
        outline(g, headerX, headerY, dialogScaleAmount(PANEL_W), dialogScaleAmount(HEADER_H));
        com.manta.api.text.MantaText.draw(g, mc.font, Component.translatable("gui.spatialaudiosystem.sound_handy.drag_panel").getString(), headerX, headerY - 11, ADJUST_ACCENT);
    }

    /** 1px の角枠。 R4.2.2: 枠線だけを 4 本の fill で描かない (radius 0 なので見た目は同じ)。 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h) {
        com.manta.api.draw.SmoothRenderer.strokeRoundedRect(g, x, y, w, h, 0f, 1f, ADJUST_ACCENT);
    }

    // ---- what the screen reads ------------------------------------------------------------

    /** The handy in the player's hand, or empty once it is put away (every action is then a no-op). */
    private static ItemStack handy() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? ItemStack.EMPTY : SoundHandyItem.held(mc.player);
    }

    private static GlobalPos selectedPos() {
        return handy().get(ModDataComponents.HANDY_SELECTED_DEVICE);
    }

    private static HandyDeviceRow selectedRow() {
        int i = HandyDeviceListClient.selectedIndex(handy());
        return i < 0 ? null : HandyDeviceListClient.rowAt(i);
    }

    /** The list index under the repeat row being resolved (window offset applied), or -1. */
    private int rowIndexAtRepeat() {
        int idx = JsonLayoutEngine.currentRepeatIndex();
        return idx < 0 ? -1 : idx + listScroll.offset();
    }

    private HandyDeviceRow rowAtRepeat() {
        int i = rowIndexAtRepeat();
        List<HandyDeviceRow> rows = HandyDeviceListClient.rows();
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    private static String nameText(HandyDeviceRow row) {
        return row.name().isEmpty()
                ? Component.translatable("gui.spatialaudiosystem.sound_handy.name_placeholder").getString()
                : row.name();
    }

    private static String posText(GlobalPos pos) {
        return pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ()
                + " · " + pos.dimension().location().getPath();
    }

    private static String statusText(HandyDeviceRow row) {
        if (!row.loaded()) return Component.translatable("gui.spatialaudiosystem.sound_handy.not_loaded").getString();
        if (row.playing()) return Component.translatable("gui.spatialaudiosystem.status_playing").getString();
        if (!row.hasMedium()) return Component.translatable("hud.spatialaudiosystem.handy_no_medium").getString();
        return Component.translatable("gui.spatialaudiosystem.status_stopped").getString();
    }

    /** The dot answers "can it play": green with a medium, red without (user's note 2026-09-05); dark when unloaded. */
    private static int dotColor(HandyDeviceRow row) {
        if (!row.loaded()) return DOT_UNLOADED;
        return row.hasMedium() ? DOT_PLAYING : DOT_NO_MEDIUM;
    }

    // ---- the push (Manta 7) ----------------------------------------------------------------

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tRowName = pushed.text("hd-row-name");
        tRowPos = pushed.text("hd-row-pos");
        tDevName = pushed.text("hd-dev-name-text");
        tDevPos = pushed.text("hd-dev-pos");
        tDevStatus = pushed.text("hd-dev-status");
        cRowBg = pushed.color("hd-row-bg");
        cRowBorder = pushed.color("hd-row-border");
        cRowDot = pushed.color("hd-row-dot");
        cNavList = pushed.color("hd-nav-list-bg");
        cNavSettings = pushed.color("hd-nav-settings-bg");
        cHudTrack = pushed.color("hd-hud-track-bg");
        cHudKnob = pushed.color("hd-hud-knob-bg");
        cLayoutTrack = pushed.color("hd-layout-track-bg");
        cLayoutKnob = pushed.color("hd-layout-knob-bg");
        cDevNameBorder = pushed.color("hd-dev-name-border");
        nCount = pushed.number("hd-count");
        nThumbY = pushed.number("hd-thumb-y");
        nHudKnobX = pushed.number("hd-hud-knob-x");
        nLayoutKnobX = pushed.number("hd-layout-knob-x");
        nDevX = pushed.number("hd-dev-x");
        bTabList = pushed.bool("hd-tab-list");
        bTabDevice = pushed.bool("hd-tab-device");
        bTabSettings = pushed.bool("hd-tab-settings");
        bScrollbar = pushed.bool("hd-scrollbar");
        bListEmpty = pushed.bool("hd-list-empty");
        // The pull answered every class-only node with its own default text: declining is the same.
        pushed.textByKeyOnly();
        // The row slots are the screen's from the first frame, rows or not: the list arrives by packet after
        // the page opens, and a player with no device never has a row - an unpushed slot would be asked of a
        // handler that answers nothing, every frame (measured 2026-09-23: `unassigned 5` on open). None is
        // what the pull answered outside a row; each row that exists is pushed below.
        pushed.clear(tRowName);
        pushed.clear(tRowPos);
        pushed.clear(cRowBg);
        pushed.clear(cRowBorder);
        pushed.clear(cRowDot);
        pushAll();
    }

    /** Every value of the page, from the expressions the pull answered with. An unchanged value costs nothing. */
    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        HandyDeviceRow sel = selectedRow();
        pushed.set(tDevName, nameInput.isFocused() ? nameInput.display() : sel == null ? "" : nameText(sel));
        pushed.set(tDevPos, sel == null ? "" : posText(sel.pos()));
        pushed.set(tDevStatus, sel == null ? "" : statusText(sel));
        if (nameInput.isFocused()) {
            pushed.set(cDevNameBorder, SELECTED_BORDER);
        } else {
            pushed.clear(cDevNameBorder);   // the pull's defaultArgb: the box's own border
        }
        pushed.set(cNavList, !pages.is(Page.SETTINGS) ? NAV_ACTIVE_BG : 0);
        pushed.set(cNavSettings, pages.is(Page.SETTINGS) ? NAV_ACTIVE_BG : 0);
        pushed.set(cHudTrack, hudToggle.trackBg());
        pushed.set(cHudKnob, hudToggle.knobBg());
        pushed.set(cLayoutTrack, layoutToggle.trackBg());
        pushed.set(cLayoutKnob, layoutToggle.knobBg());
        // Offsets: each helper is default + delta, so its delta is its answer for 0.
        pushed.setOffset(nThumbY, listScroll.thumbY(0, LIST_H - 2, THUMB_H));
        pushed.setOffset(nHudKnobX, hudToggle.knobX(0));
        pushed.setOffset(nLayoutKnobX, layoutToggle.knobX(0));
        pushed.setOffset(nDevX, Math.round(devPageOffset()));
        pushed.set(bTabList, pages.is(Page.LIST));
        pushed.set(bTabDevice, pages.is(Page.DEVICE) && sel != null);
        pushed.set(bTabSettings, pages.is(Page.SETTINGS));
        pushed.set(bScrollbar, pages.is(Page.LIST) && listScroll.needsScrollbar());
        List<HandyDeviceRow> rows = HandyDeviceListClient.rows();
        pushed.set(bListEmpty, rows.isEmpty());
        int count = pages.is(Page.LIST) ? listScroll.rowCount() : 0;
        pushed.set(nCount, count);
        int selected = HandyDeviceListClient.selectedIndex(handy());
        for (int r = 0; r < count; r++) {
            int i = r + listScroll.offset();   // the window's offset, as rowIndexAtRepeat applies it
            HandyDeviceRow row = i >= 0 && i < rows.size() ? rows.get(i) : null;
            pushed.set(tRowName, r, row == null ? "" : nameText(row));
            pushed.set(tRowPos, r, row == null ? "" : posText(row.pos()));
            if (i == selected) {
                pushed.set(cRowBg, r, SELECTED_BG);
                pushed.set(cRowBorder, r, SELECTED_BORDER);
            } else {
                pushed.clear(cRowBg, r);   // the pull's defaultArgb: the row's own colour
                pushed.clear(cRowBorder, r);
            }
            if (row == null) {
                pushed.clear(cRowDot, r);
            } else {
                pushed.set(cRowDot, r, dotColor(row));
            }
        }
    }

    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        if ("hd-list-scroll".equals(key)) {
            if (!pages.is(Page.LIST)) return false;
            listScroll.scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.onElementWheel(classes, key, mouseX, mouseY, scrollY);
    }

    @Override
    protected void handleMainClick(String[] classes, int mouseX, int mouseY, int button) {
        if (devSlidingOut) return;   // the page the player just left must not act on the way out
        if (hudToggle.handleClick(classes)) return;
        if (layoutToggle.handleClick(classes)) return;
        for (String c : classes) {
            if ("hd-layout-reset".equals(c)) {
                SoundHandyLayoutState.reset();
                return;
            }
            switch (c) {
                // The base closes an open overlay on this class and otherwise hands it here.
                case "mc-popup-close": onClose(); return;
                case "hd-row": {
                    HandyDeviceRow r = rowAtRepeat();
                    if (r != null) {
                        select(r.pos());
                        showPage(Page.DEVICE);
                    }
                    return;
                }
                case "hd-dev-back": showPage(Page.LIST); return;
                case "hd-nav-list": showPage(Page.LIST); return;
                case "hd-nav-settings": showPage(Page.SETTINGS); return;
                case "hd-dev-name-box": beginName(); return;
                case "hd-dev-play": sendAtSelected(HandyActions.PLAY); return;
                case "hd-dev-stop": sendAtSelected(HandyActions.STOP); return;
                case "hd-dev-test": sendAtSelected(HandyActions.TEST); return;
                case "hd-dev-test-stop":
                    if (!handy().isEmpty()) {
                        HandyClient.action(HandyActions.STOP_TEST, 0, null);
                    }
                    return;
                case "hd-dev-open": openRemote(); return;
                default:
                    break;
            }
        }
    }

    // ---- actions --------------------------------------------------------------------------

    private void showPage(Page next) {
        if (nameInput.isFocused()) cancelName();
        if (pages.is(next) && !devSlidingOut) return;
        if (next == Page.DEVICE) {
            // In from the left; the list underneath is hidden by the page's own visibility.
            pages.setCurrent(Page.DEVICE);
            pendingPage = null;
            devSlidingOut = false;
            devSlideNano = System.nanoTime();
        } else if (pages.is(Page.DEVICE)) {
            // Out to the left first; the switch happens when the slide has finished (render()).
            pendingPage = next;
            devSlidingOut = true;
            devSlideNano = System.nanoTime();
        } else {
            pages.setCurrent(next);
        }
        listScroll.clamp();
    }

    /** The device page's x offset for this frame: negative while entering or leaving, 0 at rest. */
    private float devPageOffset() {
        if (!pages.is(Page.DEVICE)) return 0f;
        float t = Math.min(1f, (System.nanoTime() - devSlideNano) / (float) DEV_SLIDE_NANOS);
        if (devSlidingOut) return -PANEL_W * easeOut(t);
        return -PANEL_W * (1f - easeOut(t));
    }

    /** Called every frame: a finished slide-out lands on the page that was asked for. */
    private void settleDevPage() {
        if (devSlidingOut && System.nanoTime() - devSlideNano >= DEV_SLIDE_NANOS) {
            devSlidingOut = false;
            pages.setCurrent(pendingPage == null ? Page.LIST : pendingPage);
            pendingPage = null;
        }
    }

    private void select(GlobalPos pos) {
        ItemStack h = handy();
        if (h.isEmpty()) return;
        if (nameInput.isFocused()) cancelName();
        h.set(ModDataComponents.HANDY_SELECTED_DEVICE, pos);
        HandyClient.action(HandyActions.SELECT, 0, pos);
    }

    private void sendAtSelected(int action) {
        GlobalPos pos = selectedPos();
        if (handy().isEmpty()) return;
        if (pos == null) {
            // Silence here was read as "the button does nothing" on the real device (2026-09-05).
            SoundHandyHudRenderer.toast(
                    Component.translatable("message.spatialaudiosystem.sound_handy.no_selection").getString(), 0xFFFF55);
            return;
        }
        HandyClient.action(action, 0, pos);
    }

    /**
     * "Open device": the device screen reads this client's copy of the block entity, so the
     * chunk must be here. The server checks the chunk map too, but the client is the one
     * that knows what it holds - a missing copy would throw inside the menu's client
     * constructor, so it is refused here first with the same message.
     */
    private void openRemote() {
        GlobalPos pos = selectedPos();
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || handy().isEmpty() || mc.level == null) return;
        boolean here = mc.level.dimension().equals(pos.dimension())
                && mc.level.getBlockEntity(pos.pos()) instanceof PlaybackDeviceBlockEntity;
        if (!here) {
            SoundHandyHudRenderer.toast(
                    Component.translatable("message.spatialaudiosystem.sound_handy.too_far").getString(), 0xFFFF55);
            return;
        }
        // The device screen the server opens next draws this panel behind itself and returns to it.
        behind = this;
        HandyClient.action(HandyActions.OPEN, 0, pos);
    }

    private void setHudShown(boolean shown) {
        ItemStack h = handy();
        if (h.isEmpty()) return;
        if (shown) h.remove(ModDataComponents.HANDY_HUD_HIDDEN);
        else h.set(ModDataComponents.HANDY_HUD_HIDDEN, true);
        HandyClient.action(HandyActions.SET_HUD, shown ? 0 : 1, null);
    }

    private void beginName() {
        HandyDeviceRow r = selectedRow();
        if (r == null) return;
        nameInput.setValue(r.name());
        nameInput.focus();
    }

    private void submitName() {
        GlobalPos pos = selectedPos();
        if (pos != null && !handy().isEmpty()) {
            HandyClient.rename(pos, nameInput.value());
        }
        nameInput.blur();
    }

    private void cancelName() {
        nameInput.blur();
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameInput.isFocused() && nameInput.charTyped(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing) return true;
        if (nameInput.isFocused()) {
            // Enter submits and Escape cancels inside the controller; every other key is the
            // input's too (the inventory key must not close the screen mid-name).
            nameInput.keyPressed(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
