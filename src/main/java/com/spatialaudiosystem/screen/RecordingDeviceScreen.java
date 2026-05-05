package com.spatialaudiosystem.screen;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import com.spatialaudiosystem.network.AudioUploadChunkPayload;
import com.spatialaudiosystem.network.AudioUploadStartPayload;
import com.spatialaudiosystem.network.ClearAudioPayload;
import com.spatialaudiosystem.network.StartRecordingPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RecordingDeviceScreen extends AbstractContainerScreen<RecordingDeviceMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/recording_device.png");

    private static final ResourceLocation FILE_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/fileicon_nopush.png");
    private static final ResourceLocation FILE_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/fileicon_push.png");
    private static final ResourceLocation START_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/starticon_nopush.png");
    private static final ResourceLocation START_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/starticon_push.png");
    private static final ResourceLocation CANCEL_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/cansel_nopush.png");
    private static final ResourceLocation CANCEL_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/cansel_push.png");
    private static final ResourceLocation PROGRESS =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/progress_.png");

    // Info panel bounds (relative to GUI top-left)
    private static final int PANEL_X = 7;
    private static final int PANEL_Y = 8;
    private static final int PANEL_W = 80;
    private static final int PANEL_H = 58;

    private String selectedFileName = null;
    private byte[] selectedFileData = null;
    private String selectedFormat = null;

    // For file name tooltip
    private String fullFileName = null;
    private int fileTextY = 0;

    public RecordingDeviceScreen(RecordingDeviceMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelY = -999;
        this.inventoryLabelY = this.imageHeight - 94;

        RecordingDeviceBlockEntity be = menu.getBlockEntity();

        this.addRenderableWidget(new IconButton(
                this.leftPos + 96, this.topPos + 27, 18, 18,
                () -> FILE_NOPUSH, () -> FILE_PUSH, 18, 18,
                button -> openFileExplorer()
        ));

        this.addRenderableWidget(new IconButton(
                this.leftPos + 123, this.topPos + 27, 18, 18,
                () -> START_NOPUSH, () -> START_PUSH, 18, 18,
                button -> PacketDistributor.sendToServer(new StartRecordingPayload(be.getBlockPos()))
        ));

        this.addRenderableWidget(new IconButton(
                this.leftPos + 150, this.topPos + 27, 18, 18,
                () -> CANCEL_NOPUSH, () -> CANCEL_PUSH, 18, 18,
                button -> {
                    selectedFileName = null;
                    selectedFileData = null;
                    selectedFormat = null;
                    PacketDistributor.sendToServer(new ClearAudioPayload(be.getBlockPos()));
                }
        ));
    }

    private void openFileExplorer() {
        Thread fileThread = new Thread(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(3);
                filters.put(stack.UTF8("*.mp3"));
                filters.put(stack.UTF8("*.ogg"));
                filters.put(stack.UTF8("*.wav"));
                filters.flip();

                String result = TinyFileDialogs.tinyfd_openFileDialog(
                        "Select Audio File",
                        "",
                        filters,
                        "Audio Files (*.mp3, *.ogg, *.wav)",
                        false
                );

                if (result != null) {
                    Path filePath = Path.of(result);
                    try {
                        selectedFileData = Files.readAllBytes(filePath);
                        selectedFileName = filePath.getFileName().toString();
                        String name = selectedFileName.toLowerCase();
                        if (name.endsWith(".mp3")) {
                            selectedFormat = "mp3";
                        } else if (name.endsWith(".ogg")) {
                            selectedFormat = "ogg";
                        } else if (name.endsWith(".wav")) {
                            selectedFormat = "wav";
                        } else {
                            selectedFormat = "unknown";
                        }

                        RecordingDeviceBlockEntity be = menu.getBlockEntity();

                        // Send as chunked upload to avoid packet size limit
                        int totalSize = selectedFileData.length;
                        int chunkSize = AudioUploadChunkPayload.CHUNK_SIZE;
                        int chunkCount = (totalSize + chunkSize - 1) / chunkSize;

                        PacketDistributor.sendToServer(new AudioUploadStartPayload(
                                be.getBlockPos(), selectedFileName, selectedFormat, totalSize, chunkCount));

                        for (int i = 0; i < chunkCount; i++) {
                            int offset = i * chunkSize;
                            int len = Math.min(chunkSize, totalSize - offset);
                            byte[] chunk = new byte[len];
                            System.arraycopy(selectedFileData, offset, chunk, 0, len);
                            PacketDistributor.sendToServer(new AudioUploadChunkPayload(i, chunk));
                        }

                        SpatialAudioSystem.LOGGER.info("Selected audio file: {} ({} chunks)", selectedFileName, chunkCount);
                    } catch (IOException e) {
                        SpatialAudioSystem.LOGGER.error("Failed to read audio file", e);
                        selectedFileData = null;
                        selectedFileName = null;
                        selectedFormat = null;
                    }
                }
            }
        }, "SSS-FileChooser");
        fileThread.setDaemon(true);
        fileThread.start();
    }

    private String trimToFit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        for (int i = text.length() - 1; i > 0; i--) {
            if (this.font.width(text.substring(0, i)) + ellipsisWidth <= maxWidth) {
                return text.substring(0, i) + ellipsis;
            }
        }
        return ellipsis;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.enableBlend();
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
        RenderSystem.disableBlend();

        int progress = this.menu.getRecordingProgress();
        int maxProgress = this.menu.getMaxRecordingProgress();
        if (maxProgress > 0 && progress > 0) {
            int progressWidth = (int) (28.0f * progress / maxProgress);
            RenderSystem.enableBlend();
            guiGraphics.blit(PROGRESS, this.leftPos + 118, this.topPos + 56,
                    0, 0, progressWidth, 12, 28, 12);
            RenderSystem.disableBlend();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int panelX = this.leftPos + PANEL_X;
        int panelY = this.topPos + PANEL_Y;
        int textX = panelX + 2;
        int textY = panelY + 10;
        int textMaxW = PANEL_W - 4;

        RecordingDeviceBlockEntity be = menu.getBlockEntity();

        // Draw machine name above buttons area
        String machineName = trimToFit(this.title.getString(), 80);
        int machineNameWidth = this.font.width(machineName);
        int machineNameX = this.leftPos + 88 + (80 - machineNameWidth) / 2;
        guiGraphics.drawString(this.font, machineName, machineNameX, this.topPos + 12, 0x404040, false);

        // Enable scissor to clip text within panel
        guiGraphics.enableScissor(
                this.leftPos + PANEL_X, this.topPos + PANEL_Y,
                this.leftPos + PANEL_X + PANEL_W, this.topPos + PANEL_Y + PANEL_H);

        if (menu.isRecording()) {
            String statusText = trimToFit(Component.translatable("gui.spatialaudiosystem.status_writing").getString(), textMaxW);
            guiGraphics.drawString(this.font, statusText, textX, textY, 0x55FF55, false);
        } else {
            String statusText = trimToFit(Component.translatable("gui.spatialaudiosystem.status_ready").getString(), textMaxW);
            guiGraphics.drawString(this.font, statusText, textX, textY, 0xAAAAAA, false);
        }

        fullFileName = null;
        fileTextY = textY + 12;
        String displayFileName = selectedFileName;
        if (displayFileName == null) {
            displayFileName = be.getPendingFileName();
        }
        if (displayFileName != null) {
            fullFileName = displayFileName;
            String fileLabel = Component.translatable("gui.spatialaudiosystem.file_prefix", displayFileName).getString();
            String fileText = trimToFit(fileLabel, textMaxW);
            guiGraphics.drawString(this.font, fileText, textX, fileTextY, 0x55FFFF, false);

            String fmt = selectedFormat;
            if (fmt == null) fmt = "---";
            String typeLabel = Component.translatable("gui.spatialaudiosystem.type_prefix", fmt.toUpperCase()).getString();
            String typeText = trimToFit(typeLabel, textMaxW);
            guiGraphics.drawString(this.font, typeText, textX, textY + 24, 0xFFFF55, false);
        } else {
            String noFileText = trimToFit(Component.translatable("gui.spatialaudiosystem.no_file_selected").getString(), textMaxW);
            guiGraphics.drawString(this.font, noFileText, textX, fileTextY, 0x999999, false);
        }

        int progress = this.menu.getRecordingProgress();
        int maxProgress = this.menu.getMaxRecordingProgress();
        if (maxProgress > 0 && progress > 0) {
            int pct = (int) (100.0f * progress / maxProgress);
            guiGraphics.drawString(this.font, pct + "%", textX, textY + 36, 0x55FF55, false);
        }

        guiGraphics.disableScissor();

        // File name tooltip on hover
        if (fullFileName != null) {
            int ftx = this.leftPos + PANEL_X + 2;
            int fty = fileTextY;
            if (mouseX >= ftx && mouseX <= ftx + PANEL_W - 4 && mouseY >= fty && mouseY <= fty + 9) {
                guiGraphics.renderTooltip(this.font, Component.literal(fullFileName), mouseX, mouseY);
                return;
            }
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
