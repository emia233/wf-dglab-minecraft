package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 主人客户端：把「被控者允许的上限」和当前状态回填到控制面板。
 *
 * @param valid         是否成功取到被控者（0 = 没绑人 / 对方不在线，面板应显示为断开）
 * @param comfortMax    被控者配置的舒适上限（主人设的基础强度不能超过它）
 * @param absoluteMax   被控者配置的绝对上限（惩罚电击的上限）
 * @param baseIntensity 服务端记录的主人当前基础强度
 * @param hpEnabled     被控者的血量映射是否处于开启状态
 * @param subjectName      被控者的游戏名（没绑人时是空串）
 * @param subjectConnected 被控者那边的郊狼设备是否已连接（1 = 已连接）
 */
public record PanelStatePayload(int valid, int comfortMax, int absoluteMax, int baseIntensity, int hpEnabled,
                                String subjectName, int subjectConnected)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PanelStatePayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PanelStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.valid);
                        buf.writeVarInt(p.comfortMax);
                        buf.writeVarInt(p.absoluteMax);
                        buf.writeVarInt(p.baseIntensity);
                        buf.writeVarInt(p.hpEnabled);
                        buf.writeUtf(p.subjectName == null ? "" : p.subjectName);
                        buf.writeVarInt(p.subjectConnected);
                    },
                    buf -> new PanelStatePayload(
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readUtf(), buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
