package com.civon.wildestfantasy.collar;

import com.civon.wildestfantasy.WildestFantasy;
import com.civon.wildestfantasy.controller.ServerController;
import com.civon.wildestfantasy.registry.ModItems;
import dev.yumi.commons.TriState;
import eu.pb4.trinkets.api.TrinketDropRule;
import eu.pb4.trinkets.api.event.TrinketCanEquipCallback;
import eu.pb4.trinkets.api.event.TrinketCanUnequipCallback;
import eu.pb4.trinkets.api.event.TrinketDropCallback;
import eu.pb4.trinkets.api.event.TrinketEquipCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 项圈的全部交互接线：佩戴、上锁、钥匙解锁、死亡自动解锁、掉落保护、/collaroff。
 */
public final class CollarEvents {
    private CollarEvents() {
    }

    public static void register() {
        registerEquipFeedback();
        registerEquipGuard();
        registerUnequipGuard();
        registerItemUse();
        registerEntityUse();
        registerDropProtection();
        registerDeathUnlock();
        registerCommands();

        WildestFantasy.LOGGER.info("项圈交互已注册（佩戴 / 上锁 / 钥匙解锁 / 死亡解锁 / 掉落保护）");
    }

    /** 戴上时的提示，同时补记主人（从背包拖进去的路径也要记）。 */
    private static void registerEquipFeedback() {
        TrinketEquipCallback.EVENT.register(WildestFantasy.id("collar_equip"), (stack, slot, entity) -> {
            if (!stack.is(ModItems.COLLAR) || !(entity instanceof ServerPlayer player)) {
                return;
            }
            if (CollarData.getOwner(stack).isEmpty()) {
                CollarData.setOwner(stack, player.getUUID());
            }
            player.sendSystemMessage(Component.literal(
                    "§5项圈已扣上。§7按住 §f潜行 + 跳跃 5 秒§7可以挣脱；用 §f项圈锁§7可以锁死。"));
        });
    }

    /** 项圈不许从饰品栏里手动取下：只能挣脱、主人用钥匙、死亡或安全词。 */
    private static void registerUnequipGuard() {
        TrinketCanUnequipCallback.EVENT.register(WildestFantasy.id("collar_lock"),
                (stack, slot, entity, force) -> {
                    if (!stack.is(ModItems.COLLAR) || CollarService.isForceUnlocking(entity)) {
                        return TriState.DEFAULT;
                    }
                    if (entity instanceof ServerPlayer player) {
                        if (CollarData.isLocked(stack)) {
                            player.sendSystemMessage(Component.literal(
                                    "§5项圈锁着。§7需要主人用 §f项圈钥匙§7解开，或者死亡时自动松脱。"));
                        } else {
                            player.sendSystemMessage(Component.literal(
                                    "§7项圈扣得很紧，摘不下来。§7按住 §f潜行 + 跳跃 5 秒§7试试，或者让主人用 §f项圈钥匙§7打开。"));
                        }
                    }
                    return TriState.FALSE;
                });
    }

    /** 项圈也不许从饰品栏里手动放进去：只能手持右键佩戴。 */
    private static void registerEquipGuard() {
        TrinketCanEquipCallback.EVENT.register(WildestFantasy.id("collar_wear"),
                (stack, slot, entity, force) -> {
                    if (!stack.is(ModItems.COLLAR) || CollarService.isForceEquipping(entity)) {
                        return TriState.DEFAULT;
                    }
                    if (entity instanceof ServerPlayer player) {
                        player.sendSystemMessage(Component.literal(
                                "§7项圈要戴上去：手持项圈右键空气。"));
                    }
                    return TriState.FALSE;
                });
    }

    /** 手持三种物品右键空气：戴项圈 / 上锁 / 解锁自己。 */
    private static void registerItemUse() {
        UseItemCallback.EVENT.register((Player player, Level level, InteractionHand hand) -> {
            ItemStack stack = player.getItemInHand(hand);

            if (stack.is(ModItems.COLLAR)) {
                // 客户端只负责摆手动画，真正的状态变更交给服务端
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                if (CollarService.isWearingCollar(player)) {
                    if (player instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(Component.literal("§7你脖子上已经戴着项圈了。"));
                    }
                    return InteractionResult.FAIL;
                }
                if (CollarService.equipFromHand(player, stack)) {
                    player.setItemInHand(hand, ItemStack.EMPTY);
                    return InteractionResult.SUCCESS;
                }
                if (player instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Component.literal("§7没有可用的项链槽。"));
                }
                return InteractionResult.FAIL;
            }

            if (stack.is(ModItems.COLLAR_LOCK)) {
                // 直接右键空气：给自己上锁（单人模式下 /collaroff 随时能解，不需要潜行防误触）
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                if (!(player instanceof ServerPlayer sp)) {
                    return InteractionResult.PASS;
                }
                if (!CollarService.isWearingCollar(sp)) {
                    sp.sendSystemMessage(Component.literal("§7你脖子上没有项圈，先戴上项圈。"));
                    return InteractionResult.FAIL;
                }
                if (CollarService.lockCollar(sp)) {
                    stack.shrink(1);
                    sp.sendSystemMessage(Component.literal(
                            "§5项圈已锁死。§7现在无法挣脱，只有 §f项圈钥匙§7或死亡能解开。"));
                    return InteractionResult.SUCCESS;
                }
                sp.sendSystemMessage(Component.literal("§7项圈已经锁着了。"));
                return InteractionResult.FAIL;
            }

            if (stack.is(ModItems.COLLAR_KEY)) {
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                if (!(player instanceof ServerPlayer sp)) {
                    return InteractionResult.PASS;
                }
                return unlockSelf(sp);
            }

            return InteractionResult.PASS;
        });
    }

    /** 手持钥匙右键玩家：给被锁住的玩家解锁（必须是主人）。 */
    private static void registerEntityUse() {
        UseEntityCallback.EVENT.register((Player player, Level level, InteractionHand hand,
                                          net.minecraft.world.entity.Entity entity,
                                          net.minecraft.world.phys.EntityHitResult hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!(entity instanceof Player target)) {
                return InteractionResult.PASS;
            }
            boolean collarItem = stack.is(ModItems.COLLAR);
            boolean lockItem = stack.is(ModItems.COLLAR_LOCK);
            boolean keyItem = stack.is(ModItems.COLLAR_KEY);
            if (!lockItem && !keyItem && !collarItem) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (!(player instanceof ServerPlayer sp) || !(target instanceof ServerPlayer tp)) {
                return InteractionResult.PASS;
            }

            // 项圈：优先给对面的玩家戴，自己戴走「右键空气」那条路
            if (collarItem) {
                if (CollarService.isWearingCollar(tp)) {
                    sp.sendSystemMessage(Component.literal("§7对方脖子上已经戴着项圈了。"));
                    return InteractionResult.FAIL;
                }
                if (CollarService.equipFor(tp, sp.getUUID(), stack)) {
                    stack.shrink(1);
                    // 戴上即成为主人（用户 2026-11-09 要求），这一步同时建立控制器绑定
                    ServerController.onEquipByOther(sp, tp);
                    sp.sendSystemMessage(Component.literal(
                            "§5你给 " + tp.getScoreboardName() + " 戴上了项圈，§7现在是他的主人。"));
                    tp.sendSystemMessage(Component.literal(
                            "§5" + sp.getScoreboardName() + " 给你戴上了项圈，"
                                    + "§7你的主人是 §f" + sp.getScoreboardName() + "§7。"
                                    + "按住 §f潜行 + 跳跃 5 秒§7可以挣脱；用 §f项圈锁§7可以锁死。"));
                    return InteractionResult.SUCCESS;
                }
                sp.sendSystemMessage(Component.literal("§7没有可用的项链槽。"));
                return InteractionResult.FAIL;
            }

            if (lockItem) {
                if (!CollarService.isWearingCollar(tp)) {
                    sp.sendSystemMessage(Component.literal("§7对方脖子上没有项圈。"));
                    return InteractionResult.FAIL;
                }
                if (CollarService.lockCollar(tp)) {
                    stack.shrink(1);
                    sp.sendSystemMessage(Component.literal("§5你锁住了 " + tp.getScoreboardName() + " 的项圈。"));
                    tp.sendSystemMessage(Component.literal("§5你的项圈被锁死了。§7只有主人能用钥匙解开。"));
                    return InteractionResult.SUCCESS;
                }
                sp.sendSystemMessage(Component.literal("§7对方的项圈已经锁着了。"));
                return InteractionResult.FAIL;
            }

            // 钥匙：只有主人能用，且直接收走项圈（不管有没有上锁）
            if (!CollarService.isWearingCollar(tp)) {
                sp.sendSystemMessage(Component.literal("§7对方脖子上没有项圈。"));
                return InteractionResult.FAIL;
            }
            ItemStack collar = CollarService.findCollarSlot(tp).map(s -> s.get()).orElse(ItemStack.EMPTY);
            if (!CollarService.isOwner(sp, collar)) {
                sp.sendSystemMessage(Component.literal("§c只有项圈的主人才能用钥匙解开。"));
                return InteractionResult.FAIL;
            }
            if (CollarService.transferCollar(tp, sp)) {
                sp.sendSystemMessage(Component.literal("§a你取下了 " + tp.getScoreboardName() + " 的项圈。"));
                tp.sendSystemMessage(Component.literal("§a主人用钥匙取下了你的项圈。"));
                return InteractionResult.SUCCESS;
            }
            sp.sendSystemMessage(Component.literal("§7无法取下对方的项圈。"));
            return InteractionResult.FAIL;
        });
    }

    /** 项圈永远不参与死亡掉落。 */
    private static void registerDropProtection() {
        TrinketDropCallback.EVENT.register(WildestFantasy.id("collar_keep"),
                (rule, stack, slot, entity) -> stack.is(ModItems.COLLAR) ? TrinketDropRule.KEEP : rule);
    }

    /** 死亡时项圈锁自动松脱，但项圈本身留在身上。 */
    private static void registerDeathUnlock() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return;
            }
            CollarEscape.reset(player);
            if (!CollarService.isCollarLocked(player)) {
                return;
            }
            if (CollarService.unlockCollar(player)) {
                player.sendSystemMessage(Component.literal(
                        "§7项圈锁在死亡中松脱了，项圈仍然戴在你脖子上。"));
            }
        });
    }

    /** 手持钥匙右键空气：直接摘下自己的项圈（必须是主人，也就是自己戴的那条）。 */
    private static InteractionResult unlockSelf(ServerPlayer player) {
        if (!CollarService.isWearingCollar(player)) {
            player.sendSystemMessage(Component.literal("§7你脖子上没有项圈。"));
            return InteractionResult.FAIL;
        }
        ItemStack collar = CollarService.findCollarSlot(player).map(s -> s.get()).orElse(ItemStack.EMPTY);
        if (!CollarService.isOwner(player, collar)) {
            player.sendSystemMessage(Component.literal("§c只有项圈的主人才能用钥匙解开。"));
            return InteractionResult.FAIL;
        }
        if (CollarService.removeCollar(player)) {
            player.sendSystemMessage(Component.literal("§a项圈已取下。"));
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.literal("§7无法取下项圈。"));
        return InteractionResult.FAIL;
    }

    /**
     * 调试用：/collaroff 强制脱下。
     *
     * <p>项圈主人以郊狼控制器为准：被别人用控制器绑走之后，自己不能再用指令取下，
     * 只能等主人手持钥匙（或者主人解绑，主人回到自己）。
     */
    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("collaroff")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (!CollarService.isWearingCollar(player)) {
                                context.getSource().sendFailure(
                                        Component.literal("§7你身上没有戴着项圈。"));
                                return 0;
                            }
                            ItemStack collar = CollarService.findCollarSlot(player)
                                    .map(slot -> slot.get()).orElse(ItemStack.EMPTY);
                            if (!CollarService.isOwner(player, collar)) {
                                String ownerName = CollarData.getOwner(collar)
                                        .map(uuid -> {
                                            ServerPlayer owner = player.level().getServer()
                                                    .getPlayerList().getPlayer(uuid);
                                            return owner != null ? owner.getName().getString() : "其他人";
                                        })
                                        .orElse("其他人");
                                context.getSource().sendFailure(Component.literal(
                                        "§c你的项圈由 §f" + ownerName + "§c 控制，只有主人能用钥匙取下。"));
                                return 0;
                            }
                            if (CollarService.removeCollar(player)) {
                                context.getSource().sendSuccess(
                                        () -> Component.literal("§a项圈已解开。"), false);
                                return 1;
                            }
                            context.getSource().sendFailure(
                                    Component.literal("§7你身上没有戴着项圈。"));
                            return 0;
                        })));
    }
}
