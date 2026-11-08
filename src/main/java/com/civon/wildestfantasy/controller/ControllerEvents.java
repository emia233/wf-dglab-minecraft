package com.civon.wildestfantasy.controller;

import com.civon.wildestfantasy.WildestFantasy;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 控制器物品的服务端行为：
 * <ul>
 *   <li>手持控制器右键玩家 → 绑定（要求对方戴着项圈）</li>
 *   <li>手持控制器右键空气 → 由客户端打开控制面板（不在这里处理）</li>
 *   <li>玩家下线 → 清掉运行态</li>
 * </ul>
 */
public final class ControllerEvents {
    private ControllerEvents() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!ControllerData.isController(stack)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                // 客户端只负责摆手，真正逻辑在服务端
                return InteractionResult.SUCCESS;
            }
            if (!(player instanceof ServerPlayer actor) || !(entity instanceof ServerPlayer target)) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            ServerController.tryBind(actor, target, stack);
            return InteractionResult.SUCCESS;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerController.onPlayerGone(handler.getPlayer()));

        WildestFantasy.LOGGER.info("郊狼控制器已注册（绑定 / 面板）");
    }
}
