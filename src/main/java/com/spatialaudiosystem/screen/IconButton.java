package com.spatialaudiosystem.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

public class IconButton extends AbstractButton {
    private final Supplier<ResourceLocation> normalTexture;
    private final Supplier<ResourceLocation> hoverTexture;
    private final int texWidth;
    private final int texHeight;
    private final OnPress onPress;

    public IconButton(int x, int y, int width, int height,
                      Supplier<ResourceLocation> normalTexture,
                      Supplier<ResourceLocation> hoverTexture,
                      int texWidth, int texHeight,
                      OnPress onPress) {
        super(x, y, width, height, Component.empty());
        this.normalTexture = normalTexture;
        this.hoverTexture = hoverTexture;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.onPress = onPress;
    }

    @Override
    public void onPress() {
        this.onPress.onPress(this);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ResourceLocation tex = this.isHovered ? hoverTexture.get() : normalTexture.get();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.blit(tex, this.getX(), this.getY(), 0, 0,
                this.width, this.height, texWidth, texHeight);
        RenderSystem.disableBlend();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    @FunctionalInterface
    public interface OnPress {
        void onPress(IconButton button);
    }
}
