package com.civon.wildestfantasy.feedback;

import com.civon.wildestfantasy.network.DamageTakenPayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * 把「实际受到的伤害」下发给受伤玩家自己。
 *
 * <p>为什么要在服务端采集：盔甲、抗性、附魔、吸收全部在服务端结算，客户端拿不到那个
 * 扣完之后的真实数值。而郊狼连接在客户端，所以要服务端算完再发过去。
 *
 * <p>为什么不能只看血量：有生命回复或者饱和度高的时候，伤害被瞬间补回来了，
 * 血量缺口几乎看不到 —— 但痛感是实打实吃下去了，所以必须按伤害事件算。
 */
public final class DamageFeedbackEvents {
    private DamageFeedbackEvents() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, baseDamage, damageTaken, blocked) -> {
                    if (damageTaken <= 0.0F) {
                        return;
                    }
                    if (!(entity instanceof ServerPlayer player)) {
                        return;
                    }
                    if (isContinuous(source)) {
                        // 中毒 / 凋零 / 饥饿是每 tick 掉一点的小额持续伤害，算进去会让强度一直贴着上限
                        return;
                    }
                    ServerPlayNetworking.send(player, DamageTakenPayload.of(damageTaken, kindOf(source)));
                });
    }

    /** 是不是「持续掉血」那一类（用户要求排除）。 */
    private static boolean isContinuous(DamageSource source) {
        return source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.STARVE);
    }

    /** 给这份伤害分类，客户端据此决定要不要额外叠弹射物 / 摔落反馈。 */
    private static int kindOf(DamageSource source) {
        if (source.is(DamageTypes.FALL)) {
            return DamageTakenPayload.KIND_FALL;
        }
        if (source.getDirectEntity() instanceof Projectile) {
            return DamageTakenPayload.KIND_PROJECTILE;
        }
        return DamageTakenPayload.KIND_NORMAL;
    }
}
