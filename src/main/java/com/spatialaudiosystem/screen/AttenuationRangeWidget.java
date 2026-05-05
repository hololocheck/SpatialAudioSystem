package com.spatialaudiosystem.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

public class AttenuationRangeWidget extends AbstractWidget {
    private static final int MAX_VALUE = 15;
    private int value;
    private final IntConsumer onChange;

    public AttenuationRangeWidget(int x, int y, int width, int height, int initialValue, IntConsumer onChange) {
        super(x, y, width, height, Component.empty());
        this.value = initialValue;
        this.onChange = onChange;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.isHovered && this.visible && this.active) {
            if (scrollY > 0 && value < MAX_VALUE) {
                value++;
                onChange.accept(value);
                return true;
            } else if (scrollY < 0 && value > 0) {
                value--;
                onChange.accept(value);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) return;

        int x = getX();
        int y = getY();

        // Track background
        int bgColor = this.isHovered ? 0xFF404040 : 0xFF2A2A2A;
        guiGraphics.fill(x, y, x + width, y + height, bgColor);

        // Fill bar (proportional to value)
        if (value > 0) {
            int fillWidth = (int) ((float) value / MAX_VALUE * (width - 2));
            int fillColor = this.isHovered ? 0xFF55BB55 : 0xFF44AA44;
            guiGraphics.fill(x + 1, y + 1, x + 1 + fillWidth, y + height - 1, fillColor);
        }

        // Thin border
        int borderColor = this.isHovered ? 0xFF888888 : 0xFF555555;
        guiGraphics.fill(x, y, x + width, y + 1, borderColor);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, borderColor);
        guiGraphics.fill(x, y + 1, x + 1, y + height - 1, borderColor);
        guiGraphics.fill(x + width - 1, y + 1, x + width, y + height - 1, borderColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = Math.max(0, Math.min(MAX_VALUE, value));
    }
}
