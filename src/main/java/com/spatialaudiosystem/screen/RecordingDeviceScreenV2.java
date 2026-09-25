package com.spatialaudiosystem.screen;

import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.ColorSlot;
import com.manta.api.state.MantaState;
import com.manta.api.state.NumberSlot;
import com.manta.api.state.TextSlot;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.client.AudioFilePickerService;
import com.spatialaudiosystem.client.ClientArtCache;
import com.spatialaudiosystem.client.RecordingErrorState;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import com.spatialaudiosystem.network.RecordingDeviceData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    private static final int COLOR_WRITING = 0xFF55FF55;
    private static final int COLOR_READY = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFEF5350;
    private static final int ARROW_INNER_W = 38;   // rec-arrow-track (w40) minus 1px inset each side
    private static final int FILE_MAX_W = 190;

    // ===== Manta 7 push (Phase 4, 2026-09-23) =====
    // Every value of the page is WRITTEN here, each frame before the engine draws (render): the
    // screen overrides no getDynamic*. The five texts are keyed slots (textKey = the class), the
    // arrow's fill a box (dynamicW) with one node, so its width is pushed absolute. The hint toggle
    // and the transitions are the base screen's (FrameworkState). An unchanged value costs nothing.
    private MantaState pushed;
    private TextSlot tTitle, tStatus, tFile, tType, tDuration;
    private ColorSlot cStatus, cOwner;
    private NumberSlot nArrowFill;

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

    // getDynamicText / getDynamicColor / getDynamicNumber: gone - see pushAll(). The expressions
    // are the ones the pull answered with, moved into the helpers below unchanged.

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tTitle = pushed.text("rec-title");
        tStatus = pushed.text("rec-status");
        tFile = pushed.text("rec-file");
        tType = pushed.text("rec-type");
        tDuration = pushed.text("rec-duration");
        cStatus = pushed.color("rec-status-color");
        cOwner = pushed.color("owner-border");
        nArrowFill = pushed.number("rec-arrow-fill");
        pushed.textByKeyOnly();
        pushAll();
        data();   // subscribe now: the refusal counter's snapshot must precede any press
    }

    /** Every value of the page, from the expressions the pull answered with. An unchanged value costs nothing. */
    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        pushed.set(tTitle, this.title.getString());
        pushed.set(tStatus, statusText());
        pushed.set(tFile, fileText());
        pushed.set(tType, typeText());
        pushed.set(tDuration, durationText());
        pushed.set(cStatus, statusColor());
        // OwnerAccess ring: green public / red private
        pushed.set(cOwner, com.manta.api.hud.OwnerAccess.ringColor(this.menu.getBlockEntity().isPrivateMode()));
        pushed.set(nArrowFill, com.manta.api.render.Gauge.fillWidthPercent(ARROW_INNER_W, progressPercent()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pollRefusal();
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private String statusText() {
        int err = RecordingErrorState.reasonFor(pos());
        if (err >= 0 && !this.menu.isRecording()) return startErrorText(err);
        return this.menu.isRecording()
                ? Component.translatable("gui.spatialaudiosystem.status_writing").getString()
                : Component.translatable("gui.spatialaudiosystem.status_ready").getString();
    }

    private String fileText() {
        ItemStack out = outputMedium();
        String name = out.has(ModDataComponents.AUDIO_FILE_NAME)
                ? out.get(ModDataComponents.AUDIO_FILE_NAME)
                : this.menu.getBlockEntity().getPendingFileName();
        if (name == null) {
            return Component.translatable("gui.spatialaudiosystem.no_file_selected").getString();
        }
        return trimToFit(Component.translatable(
                "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
    }

    private String typeText() {
        RecordingDeviceBlockEntity be = this.menu.getBlockEntity();
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

    private String durationText() {
        Integer sec = outputMedium().get(ModDataComponents.AUDIO_DURATION_SEC);
        if (sec == null || sec <= 0) return "";
        return Component.translatable(
                "gui.spatialaudiosystem.duration_prefix", formatDuration(sec)).getString();
    }

    private int statusColor() {
        if (!this.menu.isRecording() && RecordingErrorState.reasonFor(pos()) >= 0) return COLOR_ERROR;
        return this.menu.isRecording() ? COLOR_WRITING : COLOR_READY;
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
    protected void handleMainClick(String[] classes, int mouseX, int mouseY, int button) {
        if (com.manta.api.hud.OwnerAccess.isFaceClick(classes)) {   // toggle public/private
            sendButtonClick(com.manta.api.hud.OwnerAccess.TOGGLE_BUTTON);
            return;
        }
        for (String c : classes) {
            // hint toggle / wiki-btn / mc-popup-close は基底が先に処理する (A11)。
            if ("mc-popup-close".equals(c)) { onClose(); return; }
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
                send("start-recording");
                return;
            }
            if ("rec-clear-btn".equals(c)) {
                RecordingErrorState.clear();
                send("clear-audio");
                return;
            }
            if ("rec-play-btn".equals(c)) {
                send("test-play", true);
                return;
            }
            if ("rec-stop-btn".equals(c)) {
                send("test-play", false);
                return;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC は基底が処理する (closeOpenOverlay -> onClose)。ここで横取りすると overlay が閉じない。
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
        com.manta.api.render.ItemDraw.stackInBox(g, new ItemStack(ModItems.RECORDING_MEDIUM.get()),
                x, y, w, h, 0.62f, 0f);
    }

    /** 幅に収まるよう "…" で省略する。 実体は {@code HudText.ellipsize}。 */
    private String trimToFit(String text, int maxWidth) {
        return com.manta.api.hud.HudText.ellipsize(this.font, text, maxWidth);
    }

    // ================================================================= server sync

    /**
     * The device's host on manta:data (MANTA_7_CONCEPT C4, network.RecordingDeviceData): opened with the page and
     * closed with the screen. The refusal to start is an event on it: {@code recording-error-seq} moves and
     * {@code recording-error} holds the reason. Opened with the page, not on the first press: the snapshot must be
     * here before the press, or a snapshot and the refusal's change landing in one frame would read as history.
     */
    private com.manta.api.data.Mirror data;
    /** The refusal counter last seen; -1 until the first snapshot, whose refusal is history, not news. */
    private int seenRefusal = -1;

    private com.manta.api.data.Mirror data() {
        if (data == null) {
            RecordingDeviceBlockEntity be = this.menu.getBlockEntity();
            if (be.getLevel() == null) return null;
            data = com.manta.api.data.Mirror.open(RecordingDeviceData.channel(be.getLevel(), be.getBlockPos()),
                    RecordingDeviceData.schema());
        }
        return data;
    }

    private void send(String action, Object... args) {
        com.manta.api.data.Mirror m = data();
        if (m != null) {
            m.send(action, args);
        }
    }

    /** A refusal that arrived since the last frame is shown, as RecordingErrorPayload's handler did. */
    private void pollRefusal() {
        com.manta.api.data.Mirror m = data;
        if (m == null) return;
        m.poll();
        if (!m.ready()) return;
        int seq = (Integer) m.get("recording-error-seq");
        if (seenRefusal >= 0 && seq != seenRefusal) {
            RecordingErrorState.set(pos(), (Integer) m.get("recording-error"));
        }
        seenRefusal = seq;
    }

    @Override
    public void removed() {
        super.removed();
        if (data != null) {
            data.close();
            data = null;
        }
    }
}
