package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 被控者客户端 -> 服务端：报告自己那台机器上的郊狼设备是否已连接。
 *
 * <p>主人面板据此显示「设备已连接 / 未连接」。强度数值不在这里上报 ——
 * 主人面板不显示被控者的输出。
 *
 * @param connected 1 = 已连上 APP，0 = 未连接
 */
public record SubjectStatusPayload(int connected) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SubjectStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("subject_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubjectStatusPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeVarInt(p.connected),
                    buf -> new SubjectStatusPayload(buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
