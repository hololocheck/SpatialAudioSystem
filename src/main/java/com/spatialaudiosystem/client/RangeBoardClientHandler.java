package com.spatialaudiosystem.client;

import belugalab.tsu.api.HeldTools;
import belugalab.tsu.api.ModifierKeys;
import belugalab.tsu.api.ScrollCooldown;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.network.SetRangeBoardDataPayload;
import com.spatialaudiosystem.screen.RangeBoardHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Range Board wheel input (BELUGAEXPERIENCE_RULES §3): Alt cycles the mode, Ctrl or Shift
 * adjusts the attenuation value for the facing direction. Debounced with the standard
 * {@link ScrollCooldown} (180ms); a handled wheel is cancelled so the hotbar does not change
 * (R3.4.2 / R3.5). A modifier we do not act on is left alone, so plain hotbar scrolling still
 * works.
 *
 * <p>Both Ctrl and Shift adjust the value: Shift is the BelugaExperience standard (R3.2.3),
 * Ctrl is kept for the Range Board's existing shortcut.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class RangeBoardClientHandler {

    private static final ScrollCooldown SCROLL_COOLDOWN = new ScrollCooldown();

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = HeldTools.find(mc.player, ModItems.RANGE_BOARD.get());
        if (stack.isEmpty()) return;

        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.0001) return;

        long window = mc.getWindow().getWindow();
        boolean alt = ModifierKeys.alt(window);
        boolean adjust = !alt && (ModifierKeys.ctrl(window) || ModifierKeys.shift(window))
                && RangeBoardHudRenderer.currentMode != RangeBoardHudRenderer.MODE_NORMAL;
        if (!alt && !adjust) return;   // no wheel action for us — let the hotbar scroll

        if (!SCROLL_COOLDOWN.tryAccept()) {   // R3.4.2
            event.setCanceled(true);
            return;
        }

        if (alt) {
            // Alt + wheel: cycle mode (R3.2.1). Client-only view state shared with RangeRenderer.
            int dir = dy > 0 ? -1 : 1;
            int n = RangeBoardHudRenderer.MODE_COUNT;
            RangeBoardHudRenderer.currentMode = ((RangeBoardHudRenderer.currentMode + dir) % n + n) % n;
        } else {
            adjustAttenuation(mc, stack, dy > 0 ? 1 : -1);
        }
        event.setCanceled(true);   // R3.5
    }

    private static void adjustAttenuation(Minecraft mc, ItemStack stack, int change) {
        InteractionHand hand = mc.player.getMainHandItem().is(ModItems.RANGE_BOARD.get())
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

        List<Integer> ranges = new ArrayList<>(stack.getOrDefault(
                ModDataComponents.ATTENUATION_RANGES, ModDataComponents.DEFAULT_ATTENUATION_RANGES));
        while (ranges.size() < 6) ranges.add(8);

        int dirIdx = RangeBoardHudRenderer.getDirectionIndex(RangeBoardHudRenderer.currentMode);
        ranges.set(dirIdx, Math.max(0, Math.min(15, ranges.get(dirIdx) + change)));

        // Local update for immediate feedback, then sync to the server.
        stack.set(ModDataComponents.ATTENUATION_RANGES, new ArrayList<>(ranges));
        PacketDistributor.sendToServer(new SetRangeBoardDataPayload(hand, ranges));
    }
}
