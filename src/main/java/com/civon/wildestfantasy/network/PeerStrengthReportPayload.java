package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 被控者客户端 -> 服务端：上报自己当前实际下发到 A / B 通道的强度。
 *
 * <p>服务端转给主人，主人在 HUD 上就能看到「对方」的实时强度。
 * 只在数值变化时发，不按 tick 轮询。
 *
 * @param a A 通道强度（0–200）
 * @param b B 通道强度（0–200）
 */
public record PeerStrengthReportPayload(int a, int b) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PeerStrengthReportPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("peer_strength_report"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PeerStrengthReportPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.a);
                        buf.writeVarInt(p.b);
                    },
                    buf -> new PeerStrengthReportPayload(buf.readVarInt(), buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
