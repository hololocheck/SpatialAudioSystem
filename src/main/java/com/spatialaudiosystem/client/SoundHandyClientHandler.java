package com.spatialaudiosystem.client;

import com.manta.api.hud.HeldTools;
import com.manta.api.hud.ModifierKeys;
import com.manta.api.hud.ScrollCooldown;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.handy.SoundHandyModes;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.SoundHandyItem;
import com.spatialaudiosystem.network.HandyActionPayload;
import com.spatialaudiosystem.network.HandyDeviceListPayload;
import com.spatialaudiosystem.network.HandyRangeEditPayload;
import com.spatialaudiosystem.screen.RangeBoardHudRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The sound handy's inputs while it is held and no screen is open (spec §2, v2.0):
 *
 * <ul>
 *   <li>Shift+wheel walks the target through the owner's devices (echoed on the stack, sent
 *       as SELECT).</li>
 *   <li>Middle button toggles the target between playing and stopped (the server decides
 *       from the device's state; a device without a medium answers "cannot play").</li>
 *   <li>Shift+R toggles the range mode: the target's range is shown, and while it is on the
 *       range board in the device is edited the way the board is in the hand - Alt+wheel
 *       cycles the board's view mode, Ctrl+wheel steps the facing face's attenuation.</li>
 * </ul>
 *
 * <p>Every wheel and middle-button action ends in {@code setCanceled} (R3.5), and wheel
 * actions go through {@link ScrollCooldown} (R3.4.1/2).
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public final class SoundHandyClientHandler {
    private SoundHandyClientHandler() {}

    private static final ScrollCooldown SCROLL_COOLDOWN = new ScrollCooldown();
    private static boolean wasHolding;
    private static boolean rangeKeyDown;
    private static boolean highlightKeyDown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            wasHolding = false;
            return;
        }
        boolean holding = !HeldTools.find(mc.player, ModItems.SOUND_HANDY.get()).isEmpty();
        if (holding && !wasHolding) {
            PacketDistributor.sendToServer(HandyActionPayload.of(HandyActionPayload.REQUEST_LIST));
        }
        wasHolding = holding;
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack stack = HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        if (stack.isEmpty()) return;
        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.0001) return;
        long window = mc.getWindow().getWindow();
        boolean alt = ModifierKeys.alt(window);
        boolean ctrl = !alt && ModifierKeys.ctrl(window);
        boolean shift = !alt && !ctrl && ModifierKeys.shift(window);   // R3.3.1: Alt > Ctrl > Shift
        boolean rangeMode = SoundHandyItem.rangeMode(stack);
        // Alt and Ctrl belong to the range board's editing and only while the range mode is on;
        // Shift walks the devices always. An unmodified wheel is the hotbar's.
        if (!shift && !(rangeMode && (alt || ctrl))) return;
        if (!SCROLL_COOLDOWN.tryAccept()) {   // R3.4.2
            event.setCanceled(true);
            return;
        }
        int dir = dy > 0 ? -1 : 1;
        if (shift) {
            int current = HandyDeviceListClient.selectedIndex(stack);
            int next = SoundHandyModes.cycleSelection(current, dir, HandyDeviceListClient.rows().size());
            HandyDeviceListPayload.Row row = HandyDeviceListClient.rowAt(next);
            if (row != null) {
                // The mini HUD slides its rows the way the wheel went, wrap-around included:
                // told here, because the index order alone reads a wrap as the other way.
                com.spatialaudiosystem.screen.SoundHandyHudRenderer.hintSwitchDirection(dir);
                stack.set(ModDataComponents.HANDY_SELECTED_DEVICE, row.pos());
                PacketDistributor.sendToServer(HandyActionPayload.at(HandyActionPayload.SELECT, row.pos()));
            }
        } else if (alt) {
            int n = RangeBoardHudRenderer.MODE_COUNT;
            RangeBoardHudRenderer.currentMode = ((RangeBoardHudRenderer.currentMode + dir) % n + n) % n;
        } else if (RangeBoardHudRenderer.currentMode != RangeBoardHudRenderer.MODE_NORMAL) {
            int face = RangeBoardHudRenderer.getDirectionIndex(RangeBoardHudRenderer.currentMode);
            PacketDistributor.sendToServer(HandyRangeEditPayload.stepFace(face, dy > 0 ? 1 : -1));
        }
        event.setCanceled(true);   // R3.5
    }

    /**
     * The middle button while the handy is held: play / stop the target.
     *
     * <p>The raw button, not {@code InteractionKeyMappingTriggered}: vanilla only reaches
     * {@code pickBlock()} (where that event is fired) from one branch of {@code handleKeybinds},
     * so the earlier form never fired on the real device (2026-09-05). This is the form TSU's
     * tools use; cancelling it also keeps vanilla's pick-block from running.
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack stack = HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        if (stack.isEmpty()) return;
        PacketDistributor.sendToServer(HandyActionPayload.of(HandyActionPayload.TOGGLE_PLAY));
        event.setCanceled(true);   // R3.5: no vanilla pick block
    }

    /**
     * Shift+R toggles the range mode, Shift+H the target highlight (the device's block outlined
     * through terrain); each edge-triggered so a held key fires once.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        int key = event.getKey();
        if (key != GLFW.GLFW_KEY_R && key != GLFW.GLFW_KEY_H) return;
        boolean range = key == GLFW.GLFW_KEY_R;
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (range) rangeKeyDown = false;
            else highlightKeyDown = false;
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS || (range ? rangeKeyDown : highlightKeyDown)) return;
        if (range) rangeKeyDown = true;
        else highlightKeyDown = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if ((event.getModifiers() & GLFW.GLFW_MOD_SHIFT) == 0) return;
        ItemStack stack = HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        if (stack.isEmpty()) return;
        if (range) {
            boolean on = !SoundHandyItem.rangeMode(stack);
            if (on) stack.set(ModDataComponents.HANDY_RANGE_MODE, true);
            else stack.remove(ModDataComponents.HANDY_RANGE_MODE);
            RangeBoardHudRenderer.currentMode = RangeBoardHudRenderer.MODE_NORMAL;
            PacketDistributor.sendToServer(HandyActionPayload.of(HandyActionPayload.TOGGLE_RANGE, on ? 1 : 0));
        } else {
            boolean on = !SoundHandyItem.highlightMode(stack);
            if (on) stack.set(ModDataComponents.HANDY_HIGHLIGHT, true);
            else stack.remove(ModDataComponents.HANDY_HIGHLIGHT);
            PacketDistributor.sendToServer(HandyActionPayload.of(HandyActionPayload.TOGGLE_HIGHLIGHT, on ? 1 : 0));
        }
        eatPlainKeyClicks(mc, key);
    }

    /**
     * Shift+R belongs to the handy while it is held, so a plain-R key mapping of another mod
     * (Iris reloads shaders on R, and in-game a NONE-modifier mapping fires regardless of Shift)
     * must not fire from the same press. {@code KeyMapping.click} ran before this event, so the
     * click is already counted: consume it here, before the other mod's tick reads it.
     */
    private static void eatPlainKeyClicks(Minecraft mc, int key) {
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mapping.getKey().getValue() != key || mapping.getKey().getType() != InputConstants.Type.KEYSYM) continue;
            if (mapping.getKeyModifier() != KeyModifier.NONE) continue;
            // One press, one click: only the click this press produced is taken (review 2026-09-05).
            mapping.consumeClick();
        }
    }
}
