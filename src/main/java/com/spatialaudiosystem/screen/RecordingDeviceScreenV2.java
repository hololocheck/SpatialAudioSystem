package com.spatialaudiosystem.screen;

import belugalab.mcss3.screen.JsonLayoutScreen;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.client.AudioFilePickerService;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import com.spatialaudiosystem.network.ClearAudioPayload;
import com.spatialaudiosystem.network.StartRecordingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Recording device screen, on Manta's JSON layout engine. Replaces the hand-drawn
 * {@code RecordingDeviceScreen}; the menu (slots, quick-move, progress data) is reused.
 *
 * <p>The native file picker and upload live in {@link AudioFilePickerService}, which
 * keeps them off the render thread and drops any result that returns after this screen
 * has closed. This screen only stores what to display, and only from the render thread.
 */
public class RecordingDeviceScreenV2 extends JsonLayoutScreen<RecordingDeviceMenu> {

    private static final int COLOR_WRITING = 0xFF55FF55;
    private static final int COLOR_READY = 0xFFAAAAAA;
    private static final int PROGRESS_BAR_INNER_W = 178;
    private static final int FILE_MAX_W = 178;

    private String selectedFileName = null;
    private String selectedFormat = null;

    public RecordingDeviceScreenV2(RecordingDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected String wikiPageId() { return null; }

    @Override
    protected String layoutJson() { return SasLayouts.load("layouts/recording-device.json"); }

    private net.minecraft.core.BlockPos pos() {
        return this.menu.getBlockEntity().getBlockPos();
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        RecordingDeviceBlockEntity be = this.menu.getBlockEntity();
        for (String c : classes) {
            switch (c) {
                case "rec-title":
                    return this.title.getString();
                case "rec-status":
                    return this.menu.isRecording()
                            ? Component.translatable("gui.spatialaudiosystem.status_writing").getString()
                            : Component.translatable("gui.spatialaudiosystem.status_ready").getString();
                case "rec-file": {
                    String name = selectedFileName != null ? selectedFileName : be.getPendingFileName();
                    if (name == null) {
                        return Component.translatable("gui.spatialaudiosystem.no_file_selected").getString();
                    }
                    return trimToFit(Component.translatable(
                            "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
                }
                case "rec-type": {
                    String name = selectedFileName != null ? selectedFileName : be.getPendingFileName();
                    if (name == null) return "";
                    String fmt = selectedFormat != null ? selectedFormat : "---";
                    return Component.translatable(
                            "gui.spatialaudiosystem.type_prefix", fmt.toUpperCase()).getString();
                }
                case "rec-progress-pct": {
                    int pct = progressPercent();
                    return pct > 0 ? pct + "%" : "";
                }
                default:
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if ("rec-status-color".equals(key)) {
            return this.menu.isRecording() ? COLOR_WRITING : COLOR_READY;
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("rec-progress-fill".equals(key)) {
            return PROGRESS_BAR_INNER_W * progressPercent() / 100;
        }
        return null;
    }

    private int progressPercent() {
        int max = this.menu.getMaxRecordingProgress();
        if (max <= 0) return 0;
        return Math.max(0, Math.min(100, 100 * this.menu.getRecordingProgress() / max));
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("rec-file-btn".equals(c)) {
                AudioFilePickerService.pickAndUpload(
                        pos(),
                        () -> Minecraft.getInstance().screen == this,
                        picked -> {
                            selectedFileName = picked.fileName();
                            selectedFormat = picked.format();
                        });
                return;
            }
            if ("rec-start-btn".equals(c)) {
                PacketDistributor.sendToServer(new StartRecordingPayload(pos()));
                return;
            }
            if ("rec-clear-btn".equals(c)) {
                selectedFileName = null;
                selectedFormat = null;
                PacketDistributor.sendToServer(new ClearAudioPayload(pos()));
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
