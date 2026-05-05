package com.spatialaudiosystem.screen;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the Range Board HUD above the hotbar and handles Alt+Scroll / Ctrl+Scroll.
 *
 * Mode indices:
 *   0 = 通常範囲指定 (Normal range)
 *   1 = 減衰率設定   (Attenuation rate - direction-based with Ctrl+Scroll)
 *   2 = 下向き設定   (Downward setting - Ctrl+Scroll always sets Down)
 *
 * Attenuation ranges array: [East(+X), West(-X), Up(+Y), Down(-Y), South(+Z), North(-Z)]
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class RangeBoardHudRenderer {

    // -------- Textures --------
    private static final ResourceLocation HUD_BAR =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/range_board_hud.png");
    private static final ResourceLocation[] MODE_ICONS = {
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/range_mode_normal.png"),
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/range_mode_attenuation.png"),
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/range_mode_downward.png"),
    };

    // Bar texture dimensions
    private static final int BAR_W = 192, BAR_H = 32;
    // Icon dimensions
    private static final int ICON_W = 10, ICON_H = 10;
    // Icon X offsets within the bar (centred in each 64-px section)
    private static final int[] ICON_X_OFF = {27, 91, 155};
    private static final int ICON_Y_OFF = 11; // centred vertically in 32-px bar
    private static final int ICON_LIFT = 4;   // px to lift the selected icon

    // -------- Client-side state --------
    /** Currently selected mode (0/1/2). Shared with RangeRenderer for orange-box visibility. */
    public static int currentMode = 0;

    /** Slide animation progress: 0 = alt panel hidden, 1 = fully visible */
    private static float altProgress = 0f;
    private static long lastNano = System.nanoTime();

    /** Ticker scroll state for alt panel description */
    private static float descScrollOffset = 0f;
    private static int lastAltMode = -1;

    /** Notification message displayed between item name and bar (set by RangeBoardItem). */
    private static String notificationMessage = null;
    private static int notificationColor = 0xFFFFFF;
    private static long notificationExpiry = 0;

    // -------- Mode names (for display) --------
    private static final String[] MODE_NAMES = {
            "\u901a\u5e38\u7bc4\u56f2\u6307\u5b9a", "\u6e1b\u8870\u7387\u8a2d\u5b9a", "\u4e0b\u5411\u304d\u8a2d\u5b9a"
    };

    // -------- Mode descriptions (shown in alt panel) --------
    private static final String[] MODE_DESCRIPTIONS = {
            "2\u70b9\u3092\u9078\u629e\u3057\u3066\u7bc4\u56f2\u3092\u8a2d\u5b9a\u3057\u307e\u3059\u3002",
            "\u5411\u3044\u3066\u3044\u308b\u65b9\u5411\u306e\u6e1b\u8870\u7bc4\u56f2\u3092\u8a2d\u5b9a\u3057\u307e\u3059\u3002",
            "\u5411\u304d\u306b\u95a2\u4fc2\u306a\u304f\u4e0b\u65b9\u5411\u306e\u6e1b\u8870\u7bc4\u56f2\u3092\u8a2d\u5b9a\u3057\u307e\u3059\u3002"
    };

    // -------- Rendering --------

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // Audio gain updates and stop notifications are handled by ClientTickHandler (reliable).
        if (mc.player == null || mc.screen != null) return;

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand  = mc.player.getOffhandItem();
        boolean holdingBoard = mainHand.is(ModItems.RANGE_BOARD.get())
                || offHand.is(ModItems.RANGE_BOARD.get());
        if (!holdingBoard) {
            // Reset animation when not holding
            altProgress = 0f;
            lastNano = System.nanoTime();
            return;
        }

        // Update slide animation
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1_000_000_000f;
        lastNano = now;
        boolean altHeld = Screen.hasAltDown();
        if (altHeld) {
            altProgress = Math.min(1f, altProgress + dt * 5f); // 0.2s to open
        } else {
            altProgress = Math.max(0f, altProgress - dt * 5f); // 0.2s to close
        }

        GuiGraphics gui = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        int barX = (sw - BAR_W) / 2;
        // Main bar sits just above the alt panel gap; alt panel sits just above the hotbar
        // Layout (bottom→top): hotbar(22) | 4 | altPanel(16) | 4 | mainBar(16) | ...
        int altPanelVisibleY = sh - 22 - 4 - BAR_H;   // visible resting position
        int altPanelHiddenY  = sh + BAR_H;             // off-screen below
        int barY = altPanelVisibleY - 4 - BAR_H;       // main bar above alt panel

        // -- Alt panel (slides in from below when Alt held) --
        if (altProgress > 0f) {
            int panelY = (int) (altPanelHiddenY + (altPanelVisibleY - altPanelHiddenY) * altProgress);
            renderBar(gui, barX, panelY);

            String desc = currentMode < MODE_DESCRIPTIONS.length ? MODE_DESCRIPTIONS[currentMode] : "";
            int descW = font.width(desc);
            int maxW = BAR_W - 8;
            int descY = panelY + (BAR_H - font.lineHeight) / 2;

            if (descW <= maxW) {
                // Text fits: draw centered
                descScrollOffset = 0f;
                lastAltMode = currentMode;
                gui.drawCenteredString(font, desc, sw / 2, descY, 0xCCCCCC);
            } else {
                // Text too long: scroll like a ticker (right → left)
                if (lastAltMode != currentMode) {
                    lastAltMode = currentMode;
                    descScrollOffset = 0f;
                }
                descScrollOffset += 40f * dt; // 40 px/sec
                float totalScroll = descW + maxW;
                if (descScrollOffset > totalScroll) descScrollOffset = 0f;

                int clipX1 = barX + 4;
                int clipX2 = barX + BAR_W - 4;
                int textX = clipX2 - (int) descScrollOffset;
                gui.enableScissor(clipX1, panelY, clipX2, panelY + BAR_H);
                gui.drawString(font, desc, textX, descY, 0xCCCCCC);
                gui.disableScissor();
            }
        } else {
            // Panel hidden: reset scroll state for next open
            descScrollOffset = 0f;
            lastAltMode = -1;
        }

        // -- Main bar (always visible while holding) --
        renderBar(gui, barX, barY);

        // Icons
        for (int i = 0; i < 3; i++) {
            int ix = barX + ICON_X_OFF[i];
            int iy = barY + ICON_Y_OFF - (i == currentMode ? ICON_LIFT : 0);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            gui.blit(MODE_ICONS[i], ix, iy, 0, 0, ICON_W, ICON_H, ICON_W, ICON_H);
            RenderSystem.disableBlend();
        }

        // Selected mode name inside the bar (bottom area, under selected icon)
        // Use 0.75x scale if the text is wider than the 64-px section
        String modeName = MODE_NAMES[currentMode];
        int nameInsideY = barY + BAR_H - font.lineHeight - 2;
        int nameCx = barX + ICON_X_OFF[currentMode] + ICON_W / 2;
        int nameW = font.width(modeName);
        int sectionW = 60; // usable width within each 64-px section
        if (nameW > sectionW) {
            float scale = 0.75f;
            gui.pose().pushPose();
            gui.pose().translate(nameCx, nameInsideY, 0);
            gui.pose().scale(scale, scale, 1.0f);
            gui.drawCenteredString(font, modeName, 0, 0, 0xFFFFFF);
            gui.pose().popPose();
        } else {
            gui.drawCenteredString(font, modeName, nameCx, nameInsideY, 0xFFFFFF);
        }

        // Layout above bar (top→bottom): item name → notification → attenuation hint → bar
        boolean mainHasBoard = mainHand.is(ModItems.RANGE_BOARD.get());
        ItemStack heldBoard = mainHasBoard ? mainHand : offHand;

        // Count how many lines we need between item name and bar
        int lineCount = 0;
        boolean hasNotification = notificationMessage != null && System.currentTimeMillis() < notificationExpiry;
        boolean hasHint = currentMode != 0;
        if (hasNotification) lineCount++;
        if (hasHint) lineCount++;

        int lineH = font.lineHeight + 2;
        // Item name is always the topmost line above all others
        int itemNameY = barY - (1 + lineCount) * lineH;
        gui.drawCenteredString(font, heldBoard.getHoverName(), sw / 2, itemNameY, 0xFFFFFF);

        int nextY = itemNameY + lineH;

        // Notification (pos set / range cleared) — below item name
        if (hasNotification) {
            gui.drawCenteredString(font, notificationMessage, sw / 2, nextY, notificationColor);
            nextY += lineH;
        }

        // Attenuation hint — below notification (or below item name if no notification)
        if (hasHint) {
            int[] ranges = ModDataComponents.getAttenuationRangesArray(heldBoard);
            int dirIdx = getDirectionIndex(currentMode);
            String[] dirNames = {"\u6771", "\u897f", "\u4e0a", "\u4e0b", "\u5357", "\u5317"};
            String hint = dirNames[dirIdx] + ": " + ranges[dirIdx] + " \u30d6\u30ed\u30c3\u30af";
            gui.drawCenteredString(font, hint, sw / 2, nextY, 0xFFA500);
        }
    }

    /**
     * Show a notification message on the range board HUD (below item name, above bar).
     * Called from RangeBoardItem instead of displayClientMessage to avoid overlap.
     */
    public static void showNotification(String message, int color, int durationMs) {
        notificationMessage = message;
        notificationColor = color;
        notificationExpiry = System.currentTimeMillis() + durationMs;
    }

    /** Cancels vanilla selected-item-name rendering while holding a Range Board. */
    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!event.getName().equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.getMainHandItem().is(ModItems.RANGE_BOARD.get())
                || mc.player.getOffhandItem().is(ModItems.RANGE_BOARD.get())) {
            event.setCanceled(true);
        }
    }

    private static void renderBar(GuiGraphics gui, int x, int y) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gui.blit(HUD_BAR, x, y, 0, 0, BAR_W, BAR_H, BAR_W, BAR_H);
        RenderSystem.disableBlend();
    }

    // -------- Scroll input --------

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand  = mc.player.getOffhandItem();
        boolean mainHasBoard = mainHand.is(ModItems.RANGE_BOARD.get());
        boolean offHasBoard  = offHand.is(ModItems.RANGE_BOARD.get());
        if (!mainHasBoard && !offHasBoard) return;

        boolean altHeld  = Screen.hasAltDown();
        boolean ctrlHeld = Screen.hasControlDown();
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

        if (altHeld && !ctrlHeld) {
            // Alt + Scroll → cycle mode (scroll down = increase index: 0→1→2)
            int dir = delta > 0 ? -1 : 1;
            currentMode = ((currentMode + dir) % 3 + 3) % 3;
            event.setCanceled(true);

        } else if (ctrlHeld && !altHeld && currentMode != 0) {
            // Ctrl + Scroll → adjust attenuation range in current direction
            ItemStack board = mainHasBoard ? mainHand : offHand;
            InteractionHand hand = mainHasBoard ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

            List<Integer> ranges = new ArrayList<>(
                    board.getOrDefault(ModDataComponents.ATTENUATION_RANGES,
                            ModDataComponents.DEFAULT_ATTENUATION_RANGES));
            while (ranges.size() < 6) ranges.add(8);

            int dirIdx = getDirectionIndex(currentMode);
            int change = delta > 0 ? 1 : -1;
            ranges.set(dirIdx, Math.max(0, Math.min(15, ranges.get(dirIdx) + change)));

            // Update local item for immediate visual feedback
            board.set(ModDataComponents.ATTENUATION_RANGES, new ArrayList<>(ranges));
            // Sync to server
            PacketDistributor.sendToServer(
                    new com.spatialaudiosystem.network.SetRangeBoardDataPayload(hand, ranges));
            event.setCanceled(true);
        }
    }

    // -------- Helpers --------

    /**
     * Returns the attenuation-ranges array index for the current direction.
     * [East=0, West=1, Up=2, Down=3, South=4, North=5]
     */
    static int getDirectionIndex(int mode) {
        if (mode == 2) return 3; // 下向き設定 → always Down
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        float pitch = mc.player.getXRot();
        if (pitch < -45f) return 2; // Up
        if (pitch >  45f) return 3; // Down
        Direction facing = mc.player.getDirection();
        return switch (facing) {
            case EAST  -> 0;
            case WEST  -> 1;
            case SOUTH -> 4;
            case NORTH -> 5;
            default    -> 0;
        };
    }
}
