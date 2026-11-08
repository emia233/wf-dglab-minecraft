package com.civon.wildestfantasy.client.net;

import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.controller.PeerState;
import com.civon.wildestfantasy.client.controller.RemoteState;
import com.civon.wildestfantasy.client.dglab.DamagePulse;
import com.civon.wildestfantasy.client.dglab.FeedbackPulses;
import com.civon.wildestfantasy.network.ControlActionPayload;
import com.civon.wildestfantasy.network.DamageTakenPayload;
import com.civon.wildestfantasy.network.PanelStatePayload;
import com.civon.wildestfantasy.network.PeerStrengthPayload;
import com.civon.wildestfantasy.network.PeerStrengthReportPayload;
import com.civon.wildestfantasy.network.RemoteCommandPayload;
import com.civon.wildestfantasy.network.ReportLimitsPayload;
import com.civon.wildestfantasy.network.SubjectStatusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端侧的网络出入口。
 *
 * <p>收：服务端下发的强度指令（{@link RemoteCommandPayload}）、面板状态（{@link PanelStatePayload}）、
 * 自己实际受到的伤害（{@link DamageTakenPayload}）、被控者的实时强度（{@link PeerStrengthPayload}）。
 * <p>发：自己的上限（{@link ReportLimitsPayload}）、主人操作（{@link ControlActionPayload}）、
 * 自己被控时的设备连接状态（{@link SubjectStatusPayload}）与实时强度（{@link PeerStrengthReportPayload}）。
 */
public final class ClientNetworking {
    private static volatile PanelStatePayload panel =
            new PanelStatePayload(0, 40, 200, 0, 1, "", 0);

    private ClientNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RemoteCommandPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    switch (payload.op()) {
                        case RemoteCommandPayload.OP_BASE -> RemoteState.setBase(payload.a());
                        case RemoteCommandPayload.OP_PUNISH -> RemoteState.punish(payload.a(), payload.b());
                        case RemoteCommandPayload.OP_CLEAR -> RemoteState.clear();
                        case RemoteCommandPayload.OP_HP -> RemoteState.setHpEnabled(payload.a() != 0);
                        default -> {
                        }
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(PanelStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> panel = payload));

        ClientPlayNetworking.registerGlobalReceiver(DamageTakenPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    WildestConfig cfg = WildestConfig.get();
                    float damage = payload.tenths() / 10.0F;
                    // 伤害反馈对所有伤害都生效（按倍数、2 秒线性衰减）
                    DamagePulse.add(damage, cfg.damageFeedback);
                    // 弹射物 / 摔落是额外的绝对值方波，保持 300ms 后瞬间归零
                    long now = System.currentTimeMillis();
                    switch (payload.kind()) {
                        case DamageTakenPayload.KIND_PROJECTILE ->
                                FeedbackPulses.projectile(cfg.projectileFeedback, now);
                        case DamageTakenPayload.KIND_FALL ->
                                FeedbackPulses.fall(cfg.fallFeedback, now);
                        default -> {
                        }
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(PeerStrengthPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PeerState.set(payload.a(), payload.b())));
    }

    /** 进入世界（或改了配置）时把自己的上限告诉服务端。 */
    public static void reportLimits(WildestConfig cfg) {
        if (!ClientPlayNetworking.canSend(ReportLimitsPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new ReportLimitsPayload(cfg.effectiveComfortMax(), cfg.maxIntensity));
    }

    /** 自己被控时，把自己的郊狼连接状态告诉服务端（主人面板要显示）。 */
    public static void reportSubjectStatus(boolean connected) {
        if (!ClientPlayNetworking.canSend(SubjectStatusPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new SubjectStatusPayload(connected ? 1 : 0));
    }

    /** 自己被控时，把自己实际下发的强度告诉服务端（主人 HUD 要显示）。 */
    public static void reportPeerStrength(int a, int b) {
        if (!ClientPlayNetworking.canSend(PeerStrengthReportPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new PeerStrengthReportPayload(a, b));
    }

    public static void sendAction(int op, int a, int b, int c) {
        if (!ClientPlayNetworking.canSend(ControlActionPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new ControlActionPayload(op, a, b, c));
    }

    public static PanelStatePayload panel() {
        return panel;
    }
}
