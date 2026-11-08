package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 被控玩家客户端：让客户端对自己的郊狼下手。
 *
 * <p>之所以要绕一圈，是因为郊狼设备连在<b>被控玩家自己的电脑</b>上，
 * 只有他的客户端能驱动 DGLab 连接。主人的意图必须走「客户端 -> 服务端 -> 目标客户端」。
 *
 * <p>op 语义见 {@link #OP_BASE} 等常量，a/b/c 的含义随 op 变化。
 */
public record RemoteCommandPayload(int op, int a, int b, int c) implements CustomPacketPayload {
    /** 设定持续基础强度：a = 强度（已按舒适上限夹过）。 */
    public static final int OP_BASE = 0;
    /** 惩罚电击：a = 强度，b = 持续毫秒。 */
    public static final int OP_PUNISH = 1;
    /** 施加状态效果：a = 效果序号，b = 等级，c = 持续秒数。 */
    public static final int OP_EFFECT = 2;
    /** 清空主人侧输出（基础强度归零、停止惩罚）。 */
    public static final int OP_CLEAR = 3;
    /** 切换被控者的血量映射：a = 1 开启 / 0 关闭。 */
    public static final int OP_HP = 4;

    public static final CustomPacketPayload.Type<RemoteCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("remote_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteCommandPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.op);
                        buf.writeVarInt(p.a);
                        buf.writeVarInt(p.b);
                        buf.writeVarInt(p.c);
                    },
                    buf -> new RemoteCommandPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
