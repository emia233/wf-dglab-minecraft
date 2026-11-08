package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 郊狼控制面板专用的滑块：轨道与手柄都用模组自己的贴图，
 * 尺寸与原版 slider（200×20）+ slider_handle（8×20）一致，保留了原版的拖动手感。
 */
public abstract class ThemedSlider extends AbstractSliderButton {
    private static final Identifier TEX = WildestFantasy.id("widget/slider");
    private static final Identifier TEX_HIGHLIGHTED = WildestFantasy.id("widget/slider_highlighted");
    private static final Identifier HANDLE = WildestFantasy.id("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED = WildestFantasy.id("widget/slider_handle_highlighted");
    private static final int HANDLE_WIDTH = 8;

    protected ThemedSlider(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = isHoveredOrFocused() || canChangeValue;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                highlighted ? TEX_HIGHLIGHTED : TEX, getX(), getY(), getWidth(), getHeight());

        int handleX = getX() + (int) Math.round(value * (getWidth() - HANDLE_WIDTH));
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                highlighted ? HANDLE_HIGHLIGHTED : HANDLE, handleX, getY(), HANDLE_WIDTH, getHeight());

        graphics.centeredText(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }
}
