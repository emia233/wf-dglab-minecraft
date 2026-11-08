package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 主人客户端 -> 服务端：面板上的一次操作。
 *
 * <p>服务端会重新校验「你是不是目标项圈的主人」，然后转成
 * {@link RemoteCommandPayload} 发给目标玩家。
 */
public record ControlActionPayload(int op, int a, int b, int c) implements CustomPacketPayload {
    /** 同 {@link RemoteCommandPayload#OP_BASE}。 */
    public static final int OP_BASE = RemoteCommandPayload.OP_BASE;
    public static final int OP_PUNISH = RemoteCommandPayload.OP_PUNISH;
    public static final int OP_EFFECT = RemoteCommandPayload.OP_EFFECT;
    public static final int OP_CLEAR = RemoteCommandPayload.OP_CLEAR;
    /** 仅本方向可用：请服务端把被控者的上限回填到面板。 */
    public static final int OP_QUERY = 4;
    /** 仅本方向可用：解除绑定（主人主动断开）。 */
    public static final int OP_UNBIND = 5;
    /** 仅本方向可用：单人模式下把自己设成被控者。 */
    public static final int OP_BIND_SELF = 6;
    /** 仅本方向可用：切换被控者的血量映射开关。 */
    public static final int OP_HP_TOGGLE = 7;

    public static final CustomPacketPayload.Type<ControlActionPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("control_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.op);
                        buf.writeVarInt(p.a);
                        buf.writeVarInt(p.b);
                        buf.writeVarInt(p.c);
                    },
                    buf -> new ControlActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
