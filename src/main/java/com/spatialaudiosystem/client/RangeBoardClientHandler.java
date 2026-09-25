package com.spatialaudiosystem.client;

import com.manta.api.hud.HeldTools;
import com.manta.api.hud.ScrollCooldown;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.screen.RangeBoardHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

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
        if (com.manta.api.hud.WheelInput.isDead(dy)) return;

        com.manta.api.hud.WheelInput.Mods mods = com.manta.api.hud.WheelInput.mods(mc.getWindow().getWindow());
        boolean alt = mods.alt();
        boolean adjust = (mods.ctrl() || mods.shift())
                && RangeBoardHudRenderer.currentMode != RangeBoardHudRenderer.MODE_NORMAL;
        if (!alt && !adjust) return;   // no wheel action for us — let the hotbar scroll

        if (!SCROLL_COOLDOWN.tryAccept()) {   // R3.4.2
            event.setCanceled(true);
            return;
        }

        if (alt) {
            // Alt + wheel: cycle mode (R3.2.1). Client-only view state shared with RangeRenderer.
            int dir = -com.manta.api.hud.WheelInput.direction(dy);
            int n = RangeBoardHudRenderer.MODE_COUNT;
            RangeBoardHudRenderer.currentMode = ((RangeBoardHudRenderer.currentMode + dir) % n + n) % n;
        } else {
            adjustAttenuation(mc, stack, com.manta.api.hud.WheelInput.direction(dy));
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

        // Local update for immediate feedback, then sync to the server - every face inside the board's 0..15,
        // which the action declares (the server clamped each value before; a stored value outside the range
        // would now be refused whole instead).
        for (int i = 0; i < ranges.size(); i++) ranges.set(i, Math.max(0, Math.min(15, ranges.get(i))));
        stack.set(ModDataComponents.ATTENUATION_RANGES, new ArrayList<>(ranges));
        HandyClient.send("range-board-data", hand.name(), ranges.get(0), ranges.get(1), ranges.get(2),
                ranges.get(3), ranges.get(4), ranges.get(5));
    }
}
