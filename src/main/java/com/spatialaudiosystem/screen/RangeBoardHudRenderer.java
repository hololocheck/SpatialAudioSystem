package com.spatialaudiosystem.screen;

import com.manta.api.hud.HeldTools;
import com.manta.api.hud.HudAnimState;
import com.manta.api.hud.HudChrome;
import com.manta.api.hud.HudConstants;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Range Board HUD, on the BelugaExperience HUD system (BELUGAEXPERIENCE_RULES §2), mirroring
 * TSU's {@code StationRangeToolHudRenderer}: a centre badge above the hotbar drawn with
 * {@link HudChrome} (two-layer rounded rects, no textures) and {@link HudAnimState}
 * entry/exit fade + slide.
 *
 * <p>Rows (§2.8): a mode badge, then an info row — for the range mode a hint, for the two
 * attenuation modes the facing direction and its value. A transient notification adds a row.
 *
 * <p>Wheel input lives in {@code RangeBoardClientHandler}. {@link #currentMode} stays a
 * client-only view toggle (never persisted): it drives the world outline in
 * {@code RangeRenderer}, so it is not a server-side tool mode (a deliberate §3.1 exception).
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class RangeBoardHudRenderer {

    public static final int MODE_NORMAL = 0;      // 通常範囲指定 (two-point range)
    public static final int MODE_ATTENUATION = 1; // 減衰率設定 (facing direction)
    public static final int MODE_DOWNWARD = 2;    // 下向き設定 (always Down)
    public static final int MODE_COUNT = 3;

    private static final int BADGE_W = HudConstants.BADGE_W;
    private static final int ROW_H = HudConstants.ROW_H;
    private static final int ROW_GAP = HudConstants.ROW_GAP;
    private static final int HOTBAR_TOP_OFFSET = HudConstants.HOTBAR_TOP_OFFSET;

    private static final HudAnimState anim =
            new HudAnimState(HudConstants.ENTRY_ANIM_NANOS, HudConstants.EXIT_ANIM_NANOS);

    /** Selected mode (0/1/2). Shared with RangeRenderer for orange-box visibility; not persisted. */
    public static int currentMode = MODE_NORMAL;

    // Transient notification (pos set / range cleared), set by RangeBoardItem / ClientNotifyPayload.
    private static String notificationMessage = null;
    private static int notificationColor = 0xFFFFFF;
    private static long notificationExpiry = 0;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;   // R2.3.1
        if (mc.screen != null) return;                          // R2.3.2

        // The board in the hand - or, in the sound handy's range mode (Shift+R), the board inside
        // the targeted device: the same badge and the same values, because the same edits apply
        // (user's real-device note 2026-09-05). The mini HUD is drawn by its own renderer.
        ItemStack stack = HeldTools.find(mc.player, ModItems.RANGE_BOARD.get());
        if (stack.isEmpty()) stack = handyTargetBoard(mc);
        boolean held = !stack.isEmpty();
        anim.update(held);                                      // R2.3.3
        if (!anim.shouldRender()) return;                       // R2.3.4

        float fade = anim.fade();
        float yOffset = held ? (1f - fade) * 20f : anim.exitEased() * 20f;

        boolean hasNotification = notificationMessage != null
                && System.currentTimeMillis() < notificationExpiry;
        int rows = 2 + (hasNotification ? 1 : 0);
        int totalH = ROW_H * rows + ROW_GAP * (rows - 1);

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - BADGE_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - totalH + (int) yOffset;

        HudChrome.pushUiScale(g, sw, sh);

        // Row 1: mode badge.
        renderModeBadge(g, mc, x, y, fade);

        // Row 2: mode hint (normal) or facing direction + value (attenuation).
        int yCursor = y + ROW_H + ROW_GAP;
        String info = held ? infoText(stack) : "";
        int infoColor = currentMode == MODE_NORMAL ? 0xFFAAAAAA : 0xFFFFD54F;
        HudChrome.renderInfoRow(g, mc.font, x, yCursor, BADGE_W, ROW_H, info, infoColor, fade);

        // Row 3: transient notification.
        if (hasNotification) {
            yCursor += ROW_H + ROW_GAP;
            int color = 0xFF000000 | (notificationColor & 0xFFFFFF);
            HudChrome.renderInfoRow(g, mc.font, x, yCursor, BADGE_W, ROW_H, notificationMessage, color, fade);
        }

        HudChrome.popUiScale(g);
    }

    /**
     * The range board inside the sound handy's targeted device while its range mode is on, read
     * from the client's copy of the block entity; empty when the handy is not held, the mode is
     * off, the device is not here, or it holds no board.
     */
    private static ItemStack handyTargetBoard(Minecraft mc) {
        ItemStack handy = HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        if (handy.isEmpty() || !com.spatialaudiosystem.item.SoundHandyItem.rangeMode(handy) || mc.level == null) {
            return ItemStack.EMPTY;
        }
        net.minecraft.core.GlobalPos target = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
        if (target == null || !target.dimension().equals(mc.level.dimension())) return ItemStack.EMPTY;
        if (!(mc.level.getBlockEntity(target.pos())
                instanceof com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity be)) {
            return ItemStack.EMPTY;
        }
        ItemStack board = be.getInventory().getStackInSlot(
                com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity.RANGE_SLOT);
        return board.is(ModItems.RANGE_BOARD.get()) ? board : ItemStack.EMPTY;
    }

    private static void renderModeBadge(GuiGraphics g, Minecraft mc, int x, int y, float fade) {
        int accent = switch (currentMode) {
            case MODE_ATTENUATION -> 0x66BB6A; // green
            case MODE_DOWNWARD -> 0xFFD54F;    // yellow
            default -> 0x4FC3F7;               // cyan (normal range)
        };
        int bg = ((int) (0xE0 * fade) << 24) | 0x1a1a2e;
        int border = ((int) (0xFF * fade) << 24) | accent;
        HudChrome.drawRoundedRect(g, x, y, BADGE_W, ROW_H, bg, border);

        String label = Component.translatable(switch (currentMode) {
            case MODE_ATTENUATION -> "hud.spatialaudiosystem.range_mode_attenuation";
            case MODE_DOWNWARD -> "hud.spatialaudiosystem.range_mode_downward";
            default -> "hud.spatialaudiosystem.range_mode_normal";
        }).getString();
        int fg = ((int) (0xFF * fade) << 24) | accent;
        HudChrome.drawCenteredLabel(g, mc.font, label, x, y, BADGE_W, ROW_H, fg);
    }

    private static String infoText(ItemStack stack) {
        if (currentMode == MODE_NORMAL) {
            return Component.translatable("hud.spatialaudiosystem.range_desc_normal").getString();
        }
        int dirIdx = getDirectionIndex(currentMode);
        int[] ranges = ModDataComponents.getAttenuationRangesArray(stack);
        int value = dirIdx < ranges.length ? ranges[dirIdx] : 0;
        return Component.translatable("hud.spatialaudiosystem.range_atten_fmt",
                Component.translatable(dirKey(dirIdx)).getString(), value).getString();
    }

    private static String dirKey(int dirIdx) {
        return switch (dirIdx) {
            case 0 -> "hud.spatialaudiosystem.range_dir_east";
            case 1 -> "hud.spatialaudiosystem.range_dir_west";
            case 2 -> "hud.spatialaudiosystem.range_dir_up";
            case 3 -> "hud.spatialaudiosystem.range_dir_down";
            case 4 -> "hud.spatialaudiosystem.range_dir_south";
            default -> "hud.spatialaudiosystem.range_dir_north";
        };
    }

    /**
     * Show a notification on the Range Board HUD (below the badge). Called by RangeBoardItem /
     * {@code ClientNotifyPayload}. The message is shown verbatim, so the caller supplies it
     * already localized.
     */
    public static void showNotification(String message, int color, int durationMs) {
        notificationMessage = message;
        notificationColor = color;
        notificationExpiry = System.currentTimeMillis() + durationMs;
    }

    /**
     * The attenuation-ranges index for the current direction.
     * [East=0, West=1, Up=2, Down=3, South=4, North=5]
     */
    public static int getDirectionIndex(int mode) {
        if (mode == MODE_DOWNWARD) return 3; // always Down
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        float pitch = mc.player.getXRot();
        if (pitch < -45f) return 2; // Up
        if (pitch > 45f) return 3;  // Down
        return switch (mc.player.getDirection()) {
            case EAST -> 0;
            case WEST -> 1;
            case SOUTH -> 4;
            case NORTH -> 5;
            default -> 0;
        };
    }
}
