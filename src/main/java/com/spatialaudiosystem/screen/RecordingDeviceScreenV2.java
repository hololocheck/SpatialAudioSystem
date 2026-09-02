package com.spatialaudiosystem.screen;

import com.manta.api.screen.JsonLayoutScreen;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.client.AudioFilePickerService;
import com.spatialaudiosystem.client.ClientArtCache;
import com.spatialaudiosystem.client.RecordingErrorState;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import com.spatialaudiosystem.network.ClearAudioPayload;
import com.spatialaudiosystem.network.StartRecordingPayload;
import com.spatialaudiosystem.network.TestPlayRecordingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Recording ("Memory") device screen, on Manta's JSON layout engine. The menu (slots,
 * quick-move, progress data) is reused; the native file picker and upload live in
 * {@link AudioFilePickerService}.
 *
 * <p>Header follows BELUGAEXPERIENCE §4.17 (title left; close / wiki / hint cluster right).
 * The info panel shows the finished output medium's metadata when present, otherwise the
 * pending selection. The album-art jacket is drawn by M2 into the {@code rec-jacket} frame.
 */
public class RecordingDeviceScreenV2 extends JsonLayoutScreen<RecordingDeviceMenu> {

    private static final int COLOR_WRITING = 0xFF55FF55;
    private static final int COLOR_READY = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFEF5350;
    private static final int ARROW_INNER_W = 38;   // rec-arrow-track (w40) minus 1px inset each side
    private static final int FILE_MAX_W = 190;

    public RecordingDeviceScreenV2(RecordingDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected String wikiPageId() { return "memory-device"; }

    /**
     * Wiki capture: a stand-alone screen over a dummy block entity holding sample media, so the
     * documentation shot shows a written medium rather than an empty device.
     */
    public static RecordingDeviceScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        RecordingDeviceBlockEntity be = new RecordingDeviceBlockEntity(mc.player.blockPosition(),
                com.spatialaudiosystem.block.ModBlocks.RECORDING_DEVICE.get().defaultBlockState());
        be.setLevel(mc.level);
        be.getInventory().setStackInSlot(RecordingDeviceBlockEntity.INPUT_SLOT,
                new ItemStack(ModItems.RECORDING_MEDIUM.get()));
        ItemStack written = new ItemStack(ModItems.RECORDING_MEDIUM.get());
        written.set(ModDataComponents.AUDIO_FILE_NAME, "departure_melody.mp3");
        written.set(ModDataComponents.AUDIO_FORMAT, "mp3");
        written.set(ModDataComponents.AUDIO_DURATION_SEC, 32);
        be.getInventory().setStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT, written);
        Inventory inv = new Inventory(mc.player);   // empty: keep the player's own items out of the shot
        return new RecordingDeviceScreenV2(new RecordingDeviceMenu(0, inv, be), inv,
                Component.translatable("block.spatialaudiosystem.recording_device"));
    }

    @Override
    protected String layoutJson() { return SasLayouts.load("layouts/recording-device.json"); }

    private net.minecraft.core.BlockPos pos() {
        return this.menu.getBlockEntity().getBlockPos();
    }

    /** The finished medium in the output slot, or EMPTY while none has been written. */
    private ItemStack outputMedium() {
        return this.menu.getBlockEntity().getInventory()
                .getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        RecordingDeviceBlockEntity be = this.menu.getBlockEntity();
        for (String c : classes) {
            switch (c) {
                case "rec-title":
                    return this.title.getString();
                case "rec-status": {
                    int err = RecordingErrorState.reasonFor(pos());
                    if (err >= 0 && !this.menu.isRecording()) return startErrorText(err);
                    return this.menu.isRecording()
                            ? Component.translatable("gui.spatialaudiosystem.status_writing").getString()
                            : Component.translatable("gui.spatialaudiosystem.status_ready").getString();
                }
                case "rec-file": {
                    ItemStack out = outputMedium();
                    String name = out.has(ModDataComponents.AUDIO_FILE_NAME)
                            ? out.get(ModDataComponents.AUDIO_FILE_NAME)
                            : be.getPendingFileName();
                    if (name == null) {
                        return Component.translatable("gui.spatialaudiosystem.no_file_selected").getString();
                    }
                    return trimToFit(Component.translatable(
                            "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
                }
                case "rec-type": {
                    ItemStack out = outputMedium();
                    String fmt;
                    if (out.has(ModDataComponents.AUDIO_FORMAT)) {
                        fmt = out.getOrDefault(ModDataComponents.AUDIO_FORMAT, "unknown");
                    } else if (be.getPendingFileName() != null) {
                        fmt = be.getPendingFormat() != null ? be.getPendingFormat() : "---";
                    } else {
                        return "";
                    }
                    return Component.translatable(
                            "gui.spatialaudiosystem.type_prefix", fmt.toUpperCase()).getString();
                }
                case "rec-duration": {
                    Integer sec = outputMedium().get(ModDataComponents.AUDIO_DURATION_SEC);
                    if (sec == null || sec <= 0) return "";
                    return Component.translatable(
                            "gui.spatialaudiosystem.duration_prefix", formatDuration(sec)).getString();
                }
                default:
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if ("rec-status-color".equals(key)) {
            if (!this.menu.isRecording() && RecordingErrorState.reasonFor(pos()) >= 0) return COLOR_ERROR;
            return this.menu.isRecording() ? COLOR_WRITING : COLOR_READY;
        }
        if ("owner-border".equals(key)) {   // OwnerAccess ring: green public / red private
            return com.manta.api.hud.OwnerAccess.ringColor(this.menu.getBlockEntity().isPrivateMode());
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("rec-arrow-fill".equals(key)) {
            return ARROW_INNER_W * progressPercent() / 100;
        }
        return null;
    }

    private String startErrorText(int reason) {
        String key = switch (reason) {
            case RecordingDeviceBlockEntity.START_NO_MEDIUM -> "gui.spatialaudiosystem.rec_error_no_medium";
            case RecordingDeviceBlockEntity.START_NO_FILE -> "gui.spatialaudiosystem.rec_error_no_file";
            case RecordingDeviceBlockEntity.START_OUTPUT_OCCUPIED -> "gui.spatialaudiosystem.rec_error_output_occupied";
            default -> "";
        };
        return key.isEmpty() ? "" : Component.translatable(key).getString();
    }

    private int progressPercent() {
        int max = this.menu.getMaxRecordingProgress();
        if (max <= 0) return 0;
        return Math.max(0, Math.min(100, 100 * this.menu.getRecordingProgress() / max));
    }

    private static String formatDuration(int totalSec) {
        int m = totalSec / 60, s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        if (com.manta.api.hud.OwnerAccess.isFaceClick(classes)) {   // toggle public/private
            sendButtonClick(com.manta.api.hud.OwnerAccess.TOGGLE_BUTTON);
            return;
        }
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
            if ("rec-file-btn".equals(c)) {
                RecordingErrorState.clear();
                AudioFilePickerService.pickAndUpload(
                        pos(),
                        () -> Minecraft.getInstance().screen == this,
                        picked -> { });   // the pending name/format come back via block-entity sync
                return;
            }
            if ("rec-start-btn".equals(c)) {
                RecordingErrorState.clear();
                PacketDistributor.sendToServer(new StartRecordingPayload(pos()));
                return;
            }
            if ("rec-clear-btn".equals(c)) {
                RecordingErrorState.clear();
                PacketDistributor.sendToServer(new ClearAudioPayload(pos()));
                return;
            }
            if ("rec-play-btn".equals(c)) {
                PacketDistributor.sendToServer(new TestPlayRecordingPayload(pos(), true));
                return;
            }
            if ("rec-stop-btn".equals(c)) {
                PacketDistributor.sendToServer(new TestPlayRecordingPayload(pos(), false));
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

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "rec-jacket" -> drawJacket(g, x, y, w, h);
            case "owner-face" -> com.manta.api.hud.OwnerFacePainter.draw(
                    g, x, y, w, h, this.menu.getBlockEntity().getOwnerUUID());
            default -> { }
        }
    }

    /** Draws the finished medium's cover art, requesting it once, or a music-note placeholder. */
    private void drawJacket(GuiGraphics g, int x, int y, int w, int h) {
        UUID id = outputMedium().get(ModDataComponents.AUDIO_ID);
        ClientArtCache.Art art = ClientArtCache.get(id);
        if (art != null) {
            g.flush();   // DynamicTexture blit outside the layout batch (reference: image blit flush)
            g.blit(art.loc(), x, y, w, h, 0f, 0f, art.w(), art.h(), art.w(), art.h());
            g.flush();
            return;
        }
        if (id != null) ClientArtCache.request(id);
        drawPlaceholder(g, x, y, w, h);
    }

    /** No cover art: show the recording-medium item centred in the frame. */
    private void drawPlaceholder(GuiGraphics g, int x, int y, int w, int h) {
        float scale = Math.min(w, h) * 0.62f / 16f;
        g.pose().pushPose();
        g.pose().translate(x + (w - 16 * scale) / 2f, y + (h - 16 * scale) / 2f, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(new ItemStack(ModItems.RECORDING_MEDIUM.get()), 0, 0);
        g.pose().popPose();
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
