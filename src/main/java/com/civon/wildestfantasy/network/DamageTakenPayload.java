package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 客户端：告诉这位玩家「你刚才实际受到了多少伤害」。
 *
 * <p>伤害在服务端结算（盔甲、抗性、附魔都在那边算完再给出 damageTaken），
 * 而郊狼连接在客户端，所以必须由服务端采集再下发。
 *
 * <p>数值用「十分之一点」传：{@code tenths = round(伤害 * 10)}，
 * 这样 0.5 点这种伤害不会在网络编码里被浮点截断。
 *
 * @param tenths 实际伤害 × 10
 * @param kind   伤害种类，见 {@link #KIND_NORMAL} / {@link #KIND_PROJECTILE} / {@link #KIND_FALL}，
 *               客户端据此决定要不要额外叠一份「弹射物」或「摔落」反馈
 */
public record DamageTakenPayload(int tenths, int kind) implements CustomPacketPayload {
    /** 普通伤害（近战、爆炸、魔法…），只走伤害反馈。 */
    public static final int KIND_NORMAL = 0;
    /** 弹射物造成的伤害（箭、三叉戟…），额外叠弹射物反馈。 */
    public static final int KIND_PROJECTILE = 1;
    /** 摔落伤害，额外叠摔落反馈。 */
    public static final int KIND_FALL = 2;

    public static final CustomPacketPayload.Type<DamageTakenPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("damage_taken"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DamageTakenPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.tenths);
                        buf.writeVarInt(p.kind);
                    },
                    buf -> new DamageTakenPayload(buf.readVarInt(), buf.readVarInt())
            );

    /** 方便调用的构造：直接给浮点伤害。 */
    public static DamageTakenPayload of(float damage, int kind) {
        return new DamageTakenPayload(Math.round(damage * 10.0F), kind);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
