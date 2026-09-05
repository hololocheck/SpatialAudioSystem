package com.spatialaudiosystem.screen;

import com.manta.api.hud.HeldTools;
import com.manta.api.hud.HudAnimState;
import com.manta.api.hud.HudChrome;
import com.manta.api.hud.HudCoexistentScreen;
import com.manta.api.hud.HudConstants;
import com.manta.api.hud.HudToast;
import com.manta.api.hud.HudTooltipDodge;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.client.HandyDeviceListClient;
import com.spatialaudiosystem.client.HandyTargetSwitch;
import com.spatialaudiosystem.client.SoundHandyLayoutState;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.SoundHandyItem;
import com.spatialaudiosystem.network.HandyDeviceListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The sound handy's mini HUD (spec §2.7, v2.4): a small panel in the bottom-left corner,
 * sliding in from the left while the handy is held, that names the device the handy can act on
 * right now - the playback device's own item icon, the name, "n / N"; the medium it holds (its
 * icon, coloured by format, and file name); its state; the range mode; the highlight (Shift+H).
 *
 * <p>Everything shown is read from the handy in the hand and the server-synced list, so a
 * Shift+wheel or a list push shows on the next frame. A change of target slides the rows the
 * way the wheel went ({@link HandyTargetSwitch}). There is no mode badge: the handy has no
 * tool modes (user decision 2026-09-04). Results of an action arrive as
 * {@code ClientNotifyPayload} and go through {@link #toast}, a {@link HudToast}, which shows
 * whether or not this panel is hidden by the setting.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public final class SoundHandyHudRenderer {
    private SoundHandyHudRenderer() {}

    /** The panel is narrower than the centre badge: a side panel that must not cover the view. */
    static final int PANEL_W = 190;
    private static final int ROW_H = HudConstants.ROW_H;
    private static final int ROW_GAP = HudConstants.ROW_GAP;
    private static final int LEFT_MARGIN = 8;
    /** Clear of the hotbar: the badge sits in the bottom-left corner by default. */
    private static final int BOTTOM_MARGIN = 48;
    private static final int PAD = 6;
    private static final int ICON = 16;
    /** Name, medium, state, range, highlight. */
    private static final int ROWS = 5;
    static final int PANEL_H = PAD * 2 + ROW_H * ROWS + ROW_GAP * (ROWS - 1);
    private static final int ACCENT = 0x4FC3F7;
    private static final int ACCENT_RANGE = 0xFFD54F;
    private static final int COLOR_TEXT = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFF9E9E9E;
    private static final int COLOR_PLAYING = 0xFF66BB6A;
    private static final int COLOR_WARN = 0xFFFFD54F;
    private static final int COLOR_BAD = 0xFFEF5350;
    private static final int COLOR_HIGHLIGHT = 0xFF4FC3F7;

    private static final HudAnimState anim =
            new HudAnimState(HudConstants.ENTRY_ANIM_NANOS, HudConstants.EXIT_ANIM_NANOS);
    /** Slides the badge up from under the hint tooltip, which shares its bottom-left corner. */
    private static final HudTooltipDodge dodge = new HudTooltipDodge();
    /** The target switch: which way the rows slide and how far along. */
    private static final HandyTargetSwitch switching = new HandyTargetSwitch();
    /** The row drawn last frame, and the one sliding out during a switch. */
    private static HandyDeviceListPayload.Row shownRow;
    private static HandyDeviceListPayload.Row leavingRow;
    /**
     * The device's item icon, built on first draw and kept afterwards.
     *
     * <p>Never a static initializer: {@code @EventBusSubscriber} makes FML load this class during
     * mod construction, when the block registry is not yet bound, and a {@code .get()} there
     * throws "Trying to access unbound value" inside {@code <clinit>} - which reaches the player
     * as "failed to load correctly" with the mod disabled (measured 2026-09-04 on the client).
     */
    private static ItemStack deviceIcon;
    /** One medium stack per format, so the item's own model picks the format's colour; same rule as above. */
    private static final Map<String, ItemStack> mediumIcons = new HashMap<>();

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        // Toasts are drawn once per mod from its own RenderGuiEvent.Post (HudToast's contract);
        // this is SAS's one call, so it sits before the panel's own gates.
        HudToast.render(event.getGuiGraphics());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;   // R2.3.1
        // R2.3.2, with the HudCoexistentScreen exception: the handy's own panel keeps the badge
        // (user's real-device note 2026-09-05), and TSU's transit terminal has the same shape.
        if (mc.screen != null && !(mc.screen instanceof HudCoexistentScreen)) return;
        ItemStack stack = HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        boolean held = !stack.isEmpty() && !stack.getOrDefault(ModDataComponents.HANDY_HUD_HIDDEN, false);
        anim.update(held);                                      // R2.3.3
        if (!anim.shouldRender()) return;                       // R2.3.4
        float fade = anim.fade();
        // Slide: in from beyond the left edge on entry, back out on exit.
        float travel = PANEL_W + LEFT_MARGIN;
        float xOffset = held ? -travel * (1f - anim.entryEased()) : -travel * anim.exitEased();

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = restingX() + Math.round(xOffset);
        int restY = restingY(sh);
        // The hint tooltip lives in this corner too: glide up out from under it while it shows.
        int y = restY + Math.round(dodge.update(restingX(), restY, PANEL_W, PANEL_H));
        boolean rangeMode = held && SoundHandyItem.rangeMode(stack);
        boolean highlight = held && SoundHandyItem.highlightMode(stack);
        int accent = rangeMode ? ACCENT_RANGE : ACCENT;

        // The target switch: a change of index between frames starts the slide; the row that
        // was shown becomes the one sliding out. A refreshed row of the same device never slides.
        int index = held ? HandyDeviceListClient.selectedIndex(stack) : -1;
        HandyDeviceListPayload.Row row = held ? targetRow(stack) : null;
        long now = System.nanoTime();
        if (switching.offer(index, now)) leavingRow = shownRow;
        shownRow = row;
        float progress = switching.progress(now);

        HudChrome.pushUiScale(g, sw, sh);
        drawPanel(g, mc, x, y, fade, accent, held ? stack : ItemStack.EMPTY, rangeMode, highlight,
                row, progress < 1f ? leavingRow : null, switching.direction(), progress);
        HudChrome.popUiScale(g);
    }

    /** The wheel that changed the target went this way; the next frame's slide follows it. */
    public static void hintSwitchDirection(int dir) {
        switching.hint(dir, System.nanoTime());
    }

    /** The badge's left edge at rest: the bottom-left corner plus the user's own offset. */
    public static int restingX() {
        return LEFT_MARGIN + SoundHandyLayoutState.hudOffsetX();
    }

    /** The badge's top edge at rest, measured from the bottom of the screen. */
    public static int restingY(int screenH) {
        return screenH - BOTTOM_MARGIN - PANEL_H + SoundHandyLayoutState.hudOffsetY();
    }

    private static void drawPanel(GuiGraphics g, Minecraft mc, int x, int y, float fade, int accent, ItemStack stack,
                                  boolean rangeMode, boolean highlight, HandyDeviceListPayload.Row row,
                                  HandyDeviceListPayload.Row leaving, int dir, float progress) {
        int bg = ((int) (0xE0 * fade) << 24) | 0x1a1a2e;
        int border = ((int) (0xFF * fade) << 24) | accent;
        HudChrome.drawRoundedRect(g, x, y, PANEL_W, PANEL_H, bg, border);
        if (progress < 1f) {
            // The switch: the previous target's rows slide out and the new one's slide in, the
            // way the wheel went, inside the panel - clipped to it, so nothing shows beyond the
            // frame (user's real-device note 2026-09-05). GuiGraphics' scissor is in GUI
            // coordinates; pushUiScale adds no scale, so the panel rectangle is the clip.
            int shift = PANEL_H;
            g.enableScissor(x, y, x + PANEL_W, y + PANEL_H);
            if (leaving != null) {
                drawRows(g, mc, x, y - Math.round(dir * shift * progress), fade, stack, rangeMode, highlight, leaving);
            }
            drawRows(g, mc, x, y + Math.round(dir * shift * (1f - progress)), fade, stack, rangeMode, highlight, row);
            g.disableScissor();
        } else {
            drawRows(g, mc, x, y, fade, stack, rangeMode, highlight, row);
        }
    }

    /** The five rows for one target, with their top-left at (x, y); the "no target" line when there is none. */
    private static void drawRows(GuiGraphics g, Minecraft mc, int x, int y, float fade, ItemStack stack,
                                 boolean rangeMode, boolean highlight, HandyDeviceListPayload.Row row) {
        int textX = x + PAD + ICON + 4;
        int rowY = y + PAD;
        if (row == null) {
            String none = Component.translatable("hud.spatialaudiosystem.handy_no_target").getString();
            drawText(g, mc, none, x + PAD, rowY, HudChrome.fadeAlpha(COLOR_DIM, fade));
            return;
        }
        // Row 1: the device's own item icon, its name, n / N.
        if (fade > 0.5f) g.renderItem(deviceIcon(), x + PAD, rowY + 1);
        String name = row.name().isEmpty() ? SoundHandyItem.unnamed(row.pos().pos()) : row.name();
        int n = HandyDeviceListClient.rows().size();
        int i = HandyDeviceListClient.selectedIndex(stack) + 1;
        String count = Component.translatable("hud.spatialaudiosystem.handy_count_fmt", i, n).getString();
        int countW = com.manta.api.text.MantaText.uiWidth(mc.font, count);
        int nameMax = PANEL_W - PAD * 2 - ICON - 4 - countW - 6;
        drawText(g, mc, clip(mc, name, nameMax), textX, rowY, HudChrome.fadeAlpha(COLOR_TEXT, fade));
        drawText(g, mc, count, x + PANEL_W - PAD - countW, rowY, HudChrome.fadeAlpha(COLOR_DIM, fade));
        // Row 2: the medium - its icon in the format's colour and its file name - or why it cannot play.
        rowY += ROW_H + ROW_GAP;
        if (!row.loaded()) {
            drawText(g, mc, Component.translatable("hud.spatialaudiosystem.handy_unloaded").getString(),
                    x + PAD, rowY, HudChrome.fadeAlpha(COLOR_DIM, fade));
        } else if (row.mediumFile().isEmpty()) {
            drawText(g, mc, Component.translatable("hud.spatialaudiosystem.handy_no_medium").getString(),
                    x + PAD, rowY, HudChrome.fadeAlpha(COLOR_BAD, fade));
        } else {
            if (fade > 0.5f) g.renderItem(mediumIcon(row.mediumFormat()), x + PAD, rowY + 1);
            int fileMax = PANEL_W - PAD * 2 - ICON - 4;
            drawText(g, mc, clip(mc, row.mediumFile(), fileMax), textX, rowY, HudChrome.fadeAlpha(COLOR_TEXT, fade));
        }
        // Row 3: what it is doing (nothing to say while unloaded - row 2 said so).
        rowY += ROW_H + ROW_GAP;
        if (row.loaded()) {
            String state = Component.translatable(row.playing()
                    ? "hud.spatialaudiosystem.handy_playing" : "hud.spatialaudiosystem.handy_stopped").getString();
            drawText(g, mc, state, x + PAD, rowY, HudChrome.fadeAlpha(row.playing() ? COLOR_PLAYING : COLOR_TEXT, fade));
        }
        // Row 4: the range mode, or why it cannot edit.
        rowY += ROW_H + ROW_GAP;
        String range;
        int rangeColor;
        if (row.loaded() && !row.hasBoard()) {
            range = Component.translatable("hud.spatialaudiosystem.handy_no_board").getString();
            rangeColor = COLOR_BAD;
        } else if (rangeMode) {
            range = Component.translatable("hud.spatialaudiosystem.handy_range_on").getString();
            rangeColor = COLOR_WARN;
        } else {
            range = Component.translatable("hud.spatialaudiosystem.handy_range_off").getString();
            rangeColor = COLOR_DIM;
        }
        drawText(g, mc, range, x + PAD, rowY, HudChrome.fadeAlpha(rangeColor, fade));
        // Row 5: the highlight (Shift+H) and how to toggle it.
        rowY += ROW_H + ROW_GAP;
        String hl = Component.translatable(highlight
                ? "hud.spatialaudiosystem.handy_highlight_on" : "hud.spatialaudiosystem.handy_highlight_off").getString();
        drawText(g, mc, hl, x + PAD, rowY, HudChrome.fadeAlpha(highlight ? COLOR_HIGHLIGHT : COLOR_DIM, fade));
    }

    /** The playback device's item stack, resolved on first use (never at class load). */
    static ItemStack deviceIcon() {
        ItemStack icon = deviceIcon;
        if (icon == null) {
            icon = new ItemStack(ModBlocks.PLAYBACK_DEVICE.get());
            deviceIcon = icon;
        }
        return icon;
    }

    /**
     * A recording medium of {@code format}, for its icon: the item model reads the format
     * component and shows that format's colour, so the HUD draws what the slot would.
     */
    static ItemStack mediumIcon(String format) {
        String key = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        return mediumIcons.computeIfAbsent(key, f -> {
            ItemStack s = new ItemStack(ModItems.RECORDING_MEDIUM.get());
            if (!f.isEmpty()) s.set(ModDataComponents.AUDIO_FORMAT, f);
            return s;
        });
    }

    /** UI text through Manta's raster (2.5.0), like every dialog: the bitmap font is gone from the HUD. */
    private static void drawText(GuiGraphics g, Minecraft mc, String text, int x, int rowY, int color) {
        int ty = rowY + (ROW_H - mc.font.lineHeight) / 2 + 1;
        com.manta.api.text.MantaText.draw(g, mc.font, text, x, ty, color);
    }

    /** Manta's own ellipsis fitter, which measures as the text is drawn. */
    private static String clip(Minecraft mc, String text, int maxW) {
        return com.manta.api.hud.HudText.ellipsize(mc.font, text, maxW);
    }

    private static HandyDeviceListPayload.Row targetRow(ItemStack stack) {
        int i = HandyDeviceListClient.selectedIndex(stack);
        return i < 0 ? null : HandyDeviceListClient.rowAt(i);
    }

    /**
     * A server notification, already localized: a toast while the handy is in hand, the range
     * board's own badge row otherwise (that row is only drawn while the board is held). Called
     * from {@code ClientNotifyPayload}; the held check lives here so the common payload class
     * never names a client type.
     */
    public static void route(String text, int color) {
        Minecraft mc = Minecraft.getInstance();
        boolean handy = mc.player != null && !HeldTools.find(mc.player, ModItems.SOUND_HANDY.get()).isEmpty();
        if (handy) toast(text, color);
        else RangeBoardHudRenderer.showNotification(text, color, 2000);
    }

    /**
     * The result of a handy action, as a toast. The colour is the server's hint of the kind
     * (the same three the range board uses), mapped to the toast kinds.
     */
    public static void toast(String text, int color) {
        int rgb = color & 0xFFFFFF;
        HudToast toast = switch (rgb) {
            case 0x55FF55 -> HudToast.success(text);
            case 0xFF5555 -> HudToast.error(text);
            case 0xFFFF55, 0xFFAAAA -> HudToast.warn(text);
            default -> HudToast.info(text);
        };
        toast.show();
    }
}
