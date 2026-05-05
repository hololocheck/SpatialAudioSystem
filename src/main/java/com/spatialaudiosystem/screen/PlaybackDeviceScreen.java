package com.spatialaudiosystem.screen;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.PlaybackControlPayload;
import com.spatialaudiosystem.network.ToggleAttenuationPayload;
import com.spatialaudiosystem.network.ToggleRangeDisplayPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public class PlaybackDeviceScreen extends AbstractContainerScreen<PlaybackDeviceMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/playback_device.png");

    private static final ResourceLocation START_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/starticon_nopush.png");
    private static final ResourceLocation START_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/starticon_push.png");
    private static final ResourceLocation CANCEL_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/cansel_nopush.png");
    private static final ResourceLocation CANCEL_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/cansel_push.png");
    private static final ResourceLocation ATTENUATION_NOPUSH_ON =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/attenuation_nopush_on.png");
    private static final ResourceLocation ATTENUATION_PUSH_ON =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/attenuation_push_on.png");
    private static final ResourceLocation ATTENUATION_NOPUSH_OFF =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/attenuation_nopush_off.png");
    private static final ResourceLocation ATTENUATION_PUSH_OFF =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/attenuation_push_off.png");
    private static final ResourceLocation INVISIBLE_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/invisible_nopush.png");
    private static final ResourceLocation INVISIBLE_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/invisible_push.png");
    private static final ResourceLocation VISIBLE_NOPUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/visible_nopush.png");
    private static final ResourceLocation VISIBLE_PUSH =
            ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "textures/gui/visible_push.png");

    // Info panel bounds (relative to GUI top-left)
    private static final int PANEL_X = 7;
    private static final int PANEL_Y = 8;
    private static final int PANEL_W = 80;
    private static final int PANEL_H = 58;

    private boolean rangeVisible = false;
    private boolean attenuationEnabled = true;

    // For file name tooltip
    private String fullFileName = null;
    private int fileTextY = 0;

    public PlaybackDeviceScreen(PlaybackDeviceMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelY = -999;
        this.inventoryLabelY = this.imageHeight - 94;

        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        this.rangeVisible = be.isShowRange();
        this.attenuationEnabled = be.isAttenuationMode();

        // 2x2 button grid (lower right area, aligned with bottom of info panel):
        // Top row:    [Attenuation] [Visibility]  [Range Board Slot]
        // Bottom row: [Play]        [Stop]        [Media Slot]
        this.addRenderableWidget(new IconButton(
                this.leftPos + 96, this.topPos + 29, 18, 18,
                () -> attenuationEnabled ? ATTENUATION_NOPUSH_ON : ATTENUATION_NOPUSH_OFF,
                () -> attenuationEnabled ? ATTENUATION_PUSH_ON : ATTENUATION_PUSH_OFF,
                18, 18,
                button -> {
                    attenuationEnabled = !attenuationEnabled;
                    PacketDistributor.sendToServer(new ToggleAttenuationPayload(be.getBlockPos()));
                }
        ));

        this.addRenderableWidget(new IconButton(
                this.leftPos + 115, this.topPos + 29, 18, 18,
                () -> rangeVisible ? VISIBLE_NOPUSH : INVISIBLE_NOPUSH,
                () -> rangeVisible ? VISIBLE_PUSH : INVISIBLE_PUSH,
                18, 18,
                button -> {
                    rangeVisible = !rangeVisible;
                    PacketDistributor.sendToServer(new ToggleRangeDisplayPayload(be.getBlockPos()));
                }
        ));

        this.addRenderableWidget(new IconButton(
                this.leftPos + 96, this.topPos + 48, 18, 18,
                () -> START_NOPUSH, () -> START_PUSH, 18, 18,
                button -> PacketDistributor.sendToServer(new PlaybackControlPayload(be.getBlockPos(), true))
        ));

        this.addRenderableWidget(new IconButton(
                this.leftPos + 115, this.topPos + 48, 18, 18,
                () -> CANCEL_NOPUSH, () -> CANCEL_PUSH, 18, 18,
                button -> PacketDistributor.sendToServer(new PlaybackControlPayload(be.getBlockPos(), false))
        ));

    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.enableBlend();
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
        RenderSystem.disableBlend();
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        int panelX = this.leftPos + PANEL_X;
        int panelY = this.topPos + PANEL_Y;
        int textX = panelX + 2;
        int textY = panelY + 10;
        int textMaxW = PANEL_W - 4;

        // Draw machine name in the blank area above buttons/slots
        String machineName = trimToFit(this.title.getString(), 80);
        int machineNameWidth = this.font.width(machineName);
        int machineNameX = this.leftPos + 88 + (80 - machineNameWidth) / 2;
        guiGraphics.drawString(this.font, machineName, machineNameX, this.topPos + 14, 0x404040, false);

        // Scissor clip to info panel
        guiGraphics.enableScissor(
                this.leftPos + PANEL_X, this.topPos + PANEL_Y,
                this.leftPos + PANEL_X + PANEL_W, this.topPos + PANEL_Y + PANEL_H);

        if (be.isPlaying()) {
            String statusText = trimToFit(Component.translatable("gui.spatialaudiosystem.status_playing").getString(), textMaxW);
            guiGraphics.drawString(this.font, statusText, textX, textY, 0x55FF55, false);
        } else {
            String statusText = trimToFit(Component.translatable("gui.spatialaudiosystem.status_stopped").getString(), textMaxW);
            guiGraphics.drawString(this.font, statusText, textX, textY, 0xAAAAAA, false);
        }

        fullFileName = null;
        fileTextY = textY + 12;
        ItemStack mediaStack = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        if (mediaStack.has(ModDataComponents.AUDIO_FILE_NAME)) {
            String fileName = mediaStack.get(ModDataComponents.AUDIO_FILE_NAME);
            if (fileName != null) {
                fullFileName = fileName;
                String fileLabel = Component.translatable("gui.spatialaudiosystem.file_prefix", fileName).getString();
                String fileText = trimToFit(fileLabel, textMaxW);
                guiGraphics.drawString(this.font, fileText, textX, fileTextY, 0x55FFFF, false);
            }
            String format = mediaStack.getOrDefault(ModDataComponents.AUDIO_FORMAT, "unknown");
            String formatLabel = Component.translatable("gui.spatialaudiosystem.format_prefix", format.toUpperCase()).getString();
            String formatText = trimToFit(formatLabel, textMaxW);
            guiGraphics.drawString(this.font, formatText, textX, textY + 24, 0xFFFF55, false);
        } else {
            String noMediaText = trimToFit(Component.translatable("gui.spatialaudiosystem.no_media").getString(), textMaxW);
            guiGraphics.drawString(this.font, noMediaText, textX, textY + 12, 0x999999, false);
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
