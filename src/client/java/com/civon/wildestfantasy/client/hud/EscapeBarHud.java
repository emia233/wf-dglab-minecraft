package com.civon.wildestfantasy.client.hud;

import com.civon.wildestfantasy.WildestFantasy;
import com.civon.wildestfantasy.client.WildestFantasyClient;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 按住「潜行 + 跳跃」挣脱项圈时的 5 秒进度条。
 *
 * <p>画在原版动作条的位置上：先是整条底图，再按进度把中间那块绿色贴图平铺出来。
 * 不再显示文字与秒数。
 *
 * <p>两张图放在 {@code assets/wildestfantasy/textures/gui/sprites/} 下，
 * 所以这里是 <b>sprite</b>：id 相对该目录，且必须用 {@code blitSprite} 指定渲染管线。
 */
public final class EscapeBarHud implements HudElement {
    /** 用户要求「等比例缩小到原来一半长宽」，原图 200×20 → 100×10。 */
    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 10;
    /** 底图四周的金属边框厚度，绿色只铺在框内。 */
    private static final int INSET = 2;
    /** 绿色填充块贴图的原始边长（18 缩一半）。 */
    private static final int TILE = 9;

    /** sprite id：指向 {@code textures/gui/sprites/breakfree.png}。 */
    private static final Identifier SPRITE_BG = WildestFantasy.id("breakfree");
    /** sprite id：指向 {@code textures/gui/sprites/breakfree_loading.png}。 */
    private static final Identifier SPRITE_FILL = WildestFantasy.id("breakfree_loading");

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!WildestFantasyClient.escapeBarVisible()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        int x = (screenW - BAR_WIDTH) / 2;
        int y = screenH - 72;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE_BG, x, y, BAR_WIDTH, BAR_HEIGHT);

        int innerX = x + INSET;
        int innerY = y + INSET;
        int innerW = BAR_WIDTH - INSET * 2;
        int innerH = BAR_HEIGHT - INSET * 2;
        int filled = Math.round(innerW * WildestFantasyClient.escapeBarProgress());
        if (filled <= 0) {
            return;
        }

        // 只在已完成的那一段里平铺绿色，避免末块溢出到边框外
        graphics.enableScissor(innerX, innerY, innerX + filled, innerY + innerH);
        for (int dx = 0; dx < filled; dx += TILE) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE_FILL,
                    innerX + dx, innerY, TILE, innerH);
        }
        graphics.disableScissor();
    }
}
