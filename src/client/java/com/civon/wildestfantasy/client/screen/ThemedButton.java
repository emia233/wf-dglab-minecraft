package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 郊狼控制面板专用的按钮：贴图走模组自己的一套（{@code textures/gui/sprites/widget/}），
 * 外观由美术提供，尺寸与原版 button 一致（200×20 的九宫格）。
 *
 * <p>模组配置面板仍然使用原版 {@code Button}，两套界面互不影响。
 */
public final class ThemedButton extends AbstractWidget {
    private static final Identifier TEX = WildestFantasy.id("widget/button");
    private static final Identifier TEX_HIGHLIGHTED = WildestFantasy.id("widget/button_highlighted");

    private final Runnable action;

    public ThemedButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Identifier tex = isHoveredOrFocused() ? TEX_HIGHLIGHTED : TEX;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, tex, getX(), getY(), getWidth(), getHeight());
        graphics.centeredText(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!active) {
            return;
        }
        playButtonClickSound(Minecraft.getInstance().getSoundManager());
        action.run();
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
