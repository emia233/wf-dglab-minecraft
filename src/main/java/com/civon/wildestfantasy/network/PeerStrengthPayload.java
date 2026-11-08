package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 主人客户端：被控者当前的实时强度。
 *
 * <p>{@code a} / {@code b} 为 <b>-1</b> 表示「对方没连郊狼」或者「还没有数据」，
 * 此时 HUD 显示「对方 未连接」。
 *
 * @param a A 通道强度，-1 = 未知
 * @param b B 通道强度，-1 = 未知
 */
public record PeerStrengthPayload(int a, int b) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PeerStrengthPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("peer_strength"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PeerStrengthPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.a + 1);
                        buf.writeVarInt(p.b + 1);
                    },
                    buf -> new PeerStrengthPayload(buf.readVarInt() - 1, buf.readVarInt() - 1)
            );

    /** 「对方未连接」的哨兵值。 */
    public static PeerStrengthPayload unknown() {
        return new PeerStrengthPayload(-1, -1);
    }

    public boolean known() {
        return a >= 0 || b >= 0;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
