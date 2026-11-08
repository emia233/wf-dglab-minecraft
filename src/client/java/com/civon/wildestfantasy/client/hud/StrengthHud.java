package com.civon.wildestfantasy.client.hud;

import com.civon.wildestfantasy.client.WildestFantasyClient;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.controller.PeerState;
import com.civon.wildestfantasy.client.dglab.DglabAction;
import com.civon.wildestfantasy.client.dglab.DglabV4Client;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 屏幕上实时显示两条通道当前下发的强度。
 *
 * <p>左边一块是<b>自己</b>实际下发的值（含死亡脉冲、惩罚电击、受伤反馈叠加后的结果）；
 * 右边一块是<b>被控者的</b>实时强度 —— 只有主人视角才有数据，由服务端转发。
 *
 * <p>两块共用同一个位置与字号，但显示开关、通道选择各自独立。
 */
public final class StrengthHud implements HudElement {
    private static final int COLOR_TITLE = 0xFFCCCCCC;
    private static final int COLOR_SELF = 0xFFFFFFFF;
    private static final int COLOR_PEER = 0xFF7CFF9A;
    private static final int COLOR_OFFLINE = 0xFFFF5555;
    private static final int COLOR_PEER_OFFLINE = 0xFFAAAAAA;
    private static final int LINE_HEIGHT = 10;
    private static final int GAP = 14;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        WildestConfig cfg = WildestConfig.get();
        if (!cfg.hudEnabled) {
            return;
        }
        DglabV4Client dglab = WildestFantasyClient.dglab();

        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        int x = Math.round(screenW * (cfg.hudX / 100.0f));
        int y = Math.round(screenH * (cfg.hudY / 100.0f));
        float scale = Math.max(0.5f, cfg.hudScalePercent / 100.0f);

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);

        int selfWidth = drawSelf(client, graphics, cfg, dglab);
        if (cfg.peerHudEnabled) {
            drawPeer(client, graphics, cfg, selfWidth + GAP);
        }

        graphics.pose().popMatrix();
    }

    /** 画自己那一块，返回它占的宽度（用来把对方块排到右边）。 */
    private int drawSelf(Minecraft client, GuiGraphicsExtractor graphics,
                         WildestConfig cfg, DglabV4Client dglab) {
        if (!dglab.isReady()) {
            String text = "未连接";
            graphics.text(client.font, Component.literal(text), 0, 0, COLOR_OFFLINE);
            return client.font.width(text);
        }

        boolean showA = cfg.hudChannels != 2;
        boolean showB = cfg.hudChannels != 1;
        int width = 0;
        int row = 0;
        if (showA) {
            String text = "A  " + dglab.lastSent(DglabAction.CHANNEL_A);
            graphics.text(client.font, Component.literal(text), 0, row, COLOR_SELF);
            width = Math.max(width, client.font.width(text));
            row += LINE_HEIGHT;
        }
        if (showB) {
            String text = "B  " + dglab.lastSent(DglabAction.CHANNEL_B);
            graphics.text(client.font, Component.literal(text), 0, row, COLOR_SELF);
            width = Math.max(width, client.font.width(text));
        }
        return width;
    }

    /** 画「被控」那一块：只有主人视角才有数据，没数据时显示未连接。 */
    private void drawPeer(Minecraft client, GuiGraphicsExtractor graphics,
                          WildestConfig cfg, int peerX) {
        graphics.text(client.font, Component.literal("被控"), peerX, 0, COLOR_TITLE);

        if (!PeerState.known()) {
            graphics.text(client.font, Component.literal("未连接"), peerX, LINE_HEIGHT, COLOR_PEER_OFFLINE);
            return;
        }

        boolean showA = cfg.peerHudChannels != 2;
        boolean showB = cfg.peerHudChannels != 1;
        int row = LINE_HEIGHT;
        if (showA) {
            graphics.text(client.font, Component.literal("A  " + PeerState.a()), peerX, row, COLOR_PEER);
            row += LINE_HEIGHT;
        }
        if (showB) {
            graphics.text(client.font, Component.literal("B  " + PeerState.b()), peerX, row, COLOR_PEER);
        }
    }
}
