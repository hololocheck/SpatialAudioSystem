package com.spatialaudiosystem.screen;

import belugalab.experience.controller.ToggleSwitchController;
import belugalab.mcss3.screen.JsonLayoutScreen;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.PlaybackControlPayload;
import com.spatialaudiosystem.network.ToggleAttenuationPayload;
import com.spatialaudiosystem.network.ToggleRangeDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Playback device screen, on Manta's JSON layout engine. Replaces the hand-drawn
 * {@code PlaybackDeviceScreen}; the menu (slots, quick-move) is reused unchanged.
 *
 * <p>The two toggles keep a local optimistic value so the switch animates on click
 * (R4.9.1); the block entity state syncs underneath and re-seeds them on reopen.
 */
public class PlaybackDeviceScreenV2 extends JsonLayoutScreen<PlaybackDeviceMenu> {

    private static final int COLOR_PLAYING = 0xFF55FF55;
    private static final int COLOR_STOPPED = 0xFFAAAAAA;
    private static final int FILE_MAX_W = 178;

    private boolean attenuationOn;
    private boolean rangeVisible;

    private final ToggleSwitchController attenuationToggle = new ToggleSwitchController(
            "pb-atten-track", "pb-atten-knob",
            () -> attenuationOn,
            v -> {
                attenuationOn = v;
                PacketDistributor.sendToServer(new ToggleAttenuationPayload(pos()));
            });

    private final ToggleSwitchController rangeToggle = new ToggleSwitchController(
            "pb-range-track", "pb-range-knob",
            () -> rangeVisible,
            v -> {
                rangeVisible = v;
                PacketDistributor.sendToServer(new ToggleRangeDisplayPayload(pos()));
            });

    public PlaybackDeviceScreenV2(PlaybackDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected void init() {
        super.init();
        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        this.attenuationOn = be.isAttenuationMode();
        this.rangeVisible = be.isShowRange();
    }

    @Override
    protected String wikiPageId() { return null; }

    @Override
    protected String layoutJson() { return SasLayouts.load("layouts/playback-device.json"); }

    private net.minecraft.core.BlockPos pos() {
        return this.menu.getBlockEntity().getBlockPos();
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        for (String c : classes) {
            switch (c) {
                case "pb-title":
                    return this.title.getString();
                case "pb-status":
                    return be.isPlaying()
                            ? Component.translatable("gui.spatialaudiosystem.status_playing").getString()
                            : Component.translatable("gui.spatialaudiosystem.status_stopped").getString();
                case "pb-file": {
                    ItemStack media = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
                    String name = media.get(ModDataComponents.AUDIO_FILE_NAME);
                    if (name == null) {
                        return Component.translatable("gui.spatialaudiosystem.no_media").getString();
                    }
                    return trimToFit(Component.translatable(
                            "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
                }
                case "pb-format": {
                    ItemStack media = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
                    if (!media.has(ModDataComponents.AUDIO_FILE_NAME)) return "";
                    String fmt = media.getOrDefault(ModDataComponents.AUDIO_FORMAT, "unknown");
                    return Component.translatable(
                            "gui.spatialaudiosystem.format_prefix", fmt.toUpperCase()).getString();
                }
                default:
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if ("pb-status-color".equals(key)) {
            return this.menu.getBlockEntity().isPlaying() ? COLOR_PLAYING : COLOR_STOPPED;
        }
        if ("pb-atten-track-bg".equals(key)) return attenuationToggle.trackBg();
        if ("pb-atten-knob-bg".equals(key))  return attenuationToggle.knobBg();
        if ("pb-range-track-bg".equals(key)) return rangeToggle.trackBg();
        if ("pb-range-knob-bg".equals(key))  return rangeToggle.knobBg();
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("pb-atten-knob-x".equals(key)) return attenuationToggle.knobX(defaultValue);
        if ("pb-range-knob-x".equals(key))  return rangeToggle.knobX(defaultValue);
        return null;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (attenuationToggle.handleClick(classes)) return;
        if (rangeToggle.handleClick(classes)) return;
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("pb-play-btn".equals(c)) {
                PacketDistributor.sendToServer(new PlaybackControlPayload(pos(), true));
                return;
            }
            if ("pb-stop-btn".equals(c)) {
                PacketDistributor.sendToServer(new PlaybackControlPayload(pos(), false));
                return;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyInventory != null
                && mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String trimToFit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ew = this.font.width(ellipsis);
        for (int i = text.length() - 1; i > 0; i--) {
            if (this.font.width(text.substring(0, i)) + ew <= maxWidth) {
                return text.substring(0, i) + ellipsis;
            }
        }
        return ellipsis;
    }
}
