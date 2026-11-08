package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.controller.ServerController;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * 网络包注册。双端都要跑，因此放在 main 源集。
 *
 * <p>方向约定：
 * <ul>
 *   <li>C2S：{@link ReportLimitsPayload}（上报自己的上限）、{@link ControlActionPayload}（主人操作）、
 *       {@link SubjectStatusPayload}（被控者上报设备连接状态）、{@link PeerStrengthReportPayload}（被控者上报实时强度）</li>
 *   <li>S2C：{@link RemoteCommandPayload}（让被控者客户端驱动郊狼）、{@link PanelStatePayload}（回填面板）、
 *       {@link DamageTakenPayload}（实际受到多少伤害）、{@link PeerStrengthPayload}（转给主人的实时强度）</li>
 * </ul>
 */
public final class WildestNetworking {
    private WildestNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.serverboundPlay()
                .register(ReportLimitsPayload.TYPE, ReportLimitsPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(ControlActionPayload.TYPE, ControlActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(SubjectStatusPayload.TYPE, SubjectStatusPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(PeerStrengthReportPayload.TYPE, PeerStrengthReportPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(RemoteCommandPayload.TYPE, RemoteCommandPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(PanelStatePayload.TYPE, PanelStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(DamageTakenPayload.TYPE, DamageTakenPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(PeerStrengthPayload.TYPE, PeerStrengthPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ReportLimitsPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ServerController.handleReportLimits(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ControlActionPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ServerController.handleControlAction(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(SubjectStatusPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ServerController.handleSubjectStatus(context.player(), payload.connected())));
        ServerPlayNetworking.registerGlobalReceiver(PeerStrengthReportPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ServerController.handlePeerStrength(context.player(), payload)));
    }
}
