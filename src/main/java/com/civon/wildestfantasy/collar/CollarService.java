package com.civon.wildestfantasy.collar;

import com.civon.wildestfantasy.registry.ModItems;
import eu.pb4.trinkets.api.DefaultTrinketSlots;
import eu.pb4.trinkets.api.TrinketAttachment;
import eu.pb4.trinkets.api.TrinketSlotAccess;
import eu.pb4.trinkets.api.TrinketsApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 项圈的佩戴状态、锁定与摘除。
 *
 * <p>项圈戴在 Trinkets 的 {@code chest/necklace} 槽里，和胸甲互不占用。
 * 未上锁时玩家可以「潜行 + 跳跃按住 5 秒」自己挣脱，也可以直接从饰品栏取出；
 * 一旦用项圈锁锁死，就只能由主人拿钥匙解开（或者死亡时自动解锁）。
 */
public final class CollarService {
    /** 正在执行「合法解锁」的玩家；只有他们能绕过锁定。 */
    private static final Set<UUID> FORCE_UNLOCKING = new HashSet<>();
    /** 正在执行「合法佩戴」的玩家；只有他们能绕过「不能用饰品栏手动放入」的限制。 */
    private static final Set<UUID> FORCE_EQUIPPING = new HashSet<>();

    private CollarService() {
    }

    /** 实体身上是否戴着我们的项圈。 */
    public static boolean isWearingCollar(LivingEntity entity) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(entity);
        return attachment != null && attachment.isEquipped(ModItems.COLLAR);
    }

    /** 当前是否处于「刚刚被合法解锁」的窗口，允许绕过锁定。 */
    public static boolean isForceUnlocking(LivingEntity entity) {
        return FORCE_UNLOCKING.contains(entity.getUUID());
    }

    /** 当前是否处于「代码主动佩戴」的窗口，允许绕过「不许手动拖进饰品栏」。 */
    public static boolean isForceEquipping(LivingEntity entity) {
        return FORCE_EQUIPPING.contains(entity.getUUID());
    }

    /** 找到实体身上那条项圈的槽位。 */
    public static Optional<TrinketSlotAccess> findCollarSlot(LivingEntity entity) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(entity);
        if (attachment == null) {
            return Optional.empty();
        }
        // 数组当可变容器用：lambda 里要写入，不能直接用局部变量
        TrinketSlotAccess[] found = new TrinketSlotAccess[1];
        attachment.forEach((slot, stack) -> {
            if (found[0] == null && stack.is(ModItems.COLLAR)) {
                found[0] = slot;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    /** 身上的项圈是否被锁死了。 */
    public static boolean isCollarLocked(LivingEntity entity) {
        return findCollarSlot(entity).map(slot -> CollarData.isLocked(slot.get())).orElse(false);
    }

    /** 玩家是不是这条项圈的主人（当初给他戴上项圈的人）。 */
    public static boolean isOwner(Player player, ItemStack collar) {
        return CollarData.getOwner(collar).map(id -> id.equals(player.getUUID())).orElse(false);
    }

    /**
     * 把手里拿着的项圈直接放进空的项链槽（右键佩戴）。
     *
     * <p>只找「空的、且属于 necklace 类型」的槽，不碰其他槽位。
     *
     * @return 是否成功放进去了
     */
    public static boolean equipFromHand(Player player, ItemStack stack) {
        // 自己给自己戴：主人就是自己（单人模式走这条）
        return equipFor(player, player.getUUID(), stack);
    }

    /**
     * 把项圈戴到 {@code wearer} 脖子上，并把主人记成 {@code ownerId}。
     *
     * <p>联机时是「主人手持项圈右键被控者」，此时 wearer != ownerId。
     *
     * @return 是否成功放进去了
     */
    public static boolean equipFor(LivingEntity wearer, UUID ownerId, ItemStack stack) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(wearer);
        if (attachment == null) {
            return false;
        }

        TrinketSlotAccess[] target = new TrinketSlotAccess[1];
        attachment.forEach((slot, current) -> {
            if (target[0] == null
                    && current.isEmpty()
                    && slot.slotType().getId().equals(DefaultTrinketSlots.CHEST_NECKLACE)) {
                target[0] = slot;
            }
        });
        if (target[0] == null) {
            return false;
        }

        ItemStack placed = stack.copy();
        placed.setCount(1);
        CollarData.setOwner(placed, ownerId);
        FORCE_EQUIPPING.add(wearer.getUUID());
        try {
            target[0].set(placed);
        } finally {
            FORCE_EQUIPPING.remove(wearer.getUUID());
        }
        return true;
    }

    /** 这条项圈的主人是不是 {@code playerId}。 */
    public static boolean isCollarOwner(LivingEntity entity, UUID playerId) {
        return findCollarSlot(entity)
                .map(slot -> CollarData.getOwner(slot.get()).map(playerId::equals).orElse(false))
                .orElse(false);
    }

    /** 改写项圈上记录的主人。 */
    public static boolean setCollarOwner(ServerPlayer wearer, UUID ownerId) {
        Optional<TrinketSlotAccess> slot = findCollarSlot(wearer);
        if (slot.isEmpty()) {
            return false;
        }
        ItemStack stack = slot.get().get();
        CollarData.setOwner(stack, ownerId);
        // 项圈一律不许手动取出，这里用合法窗口把改写放进去
        FORCE_UNLOCKING.add(wearer.getUUID());
        try {
            slot.get().set(stack);
        } finally {
            FORCE_UNLOCKING.remove(wearer.getUUID());
        }
        return true;
    }

    /**
     * 给玩家脖子上的项圈上锁（锁上之后无法挣脱，只能主人用钥匙解开）。
     *
     * @return 是否真的锁上了
     */
    public static boolean lockCollar(ServerPlayer player) {
        Optional<TrinketSlotAccess> slot = findCollarSlot(player);
        if (slot.isEmpty()) {
            return false;
        }
        ItemStack stack = slot.get().get();
        if (CollarData.isLocked(stack)) {
            return false;
        }
        applyLock(player, slot.get(), true);
        return true;
    }

    /**
     * 解开项圈锁。
     *
     * @return 是否真的解开了
     */
    public static boolean unlockCollar(ServerPlayer player) {
        Optional<TrinketSlotAccess> slot = findCollarSlot(player);
        if (slot.isEmpty()) {
            return false;
        }
        ItemStack stack = slot.get().get();
        if (!CollarData.isLocked(stack)) {
            return false;
        }
        applyLock(player, slot.get(), false);
        return true;
    }

    /**
     * 改锁定状态并把改动写回槽位。
     *
     * <p>写回时会短暂打开「合法解锁」窗口，否则 {@link com.civon.wildestfantasy.collar.CollarEvents}
     * 里的 can-unequip 回调会把这个写操作也一起拦掉。
     */
    private static void applyLock(ServerPlayer player, TrinketSlotAccess slot, boolean locked) {
        ItemStack stack = slot.get().copy();
        if (locked) {
            CollarData.setLocked(stack, true);
        } else {
            CollarData.clearLock(stack);
        }
        FORCE_UNLOCKING.add(player.getUUID());
        try {
            slot.set(stack);
        } finally {
            FORCE_UNLOCKING.remove(player.getUUID());
        }
    }

    /**
     * 摘下玩家身上的项圈（无视锁定），塞回他自己的背包，背包满了就掉在脚下。
     *
     * @return 是否真的摘下了东西
     */
    public static boolean removeCollar(ServerPlayer player) {
        List<ItemStack> taken = takeCollar(player);
        if (taken.isEmpty()) {
            return false;
        }
        for (ItemStack stack : taken) {
            give(player, stack);
        }
        return true;
    }

    /**
     * 把 {@code wearer} 身上的项圈摘下来交给 {@code receiver}（主人用钥匙收走）。
     *
     * @return 是否真的摘下了东西
     */
    public static boolean transferCollar(ServerPlayer wearer, Player receiver) {
        List<ItemStack> taken = takeCollar(wearer);
        if (taken.isEmpty()) {
            return false;
        }
        for (ItemStack stack : taken) {
            give(receiver, stack);
        }
        return true;
    }

    /** 把项圈从饰品槽里取下来，返回取到的物品（不改动任何人的背包）。 */
    private static List<ItemStack> takeCollar(ServerPlayer wearer) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(wearer);
        if (attachment == null) {
            return List.of();
        }

        List<TrinketSlotAccess> occupied = new ArrayList<>();
        attachment.forEach((slot, stack) -> {
            if (stack.is(ModItems.COLLAR)) {
                occupied.add(slot);
            }
        });
        if (occupied.isEmpty()) {
            return List.of();
        }

        List<ItemStack> taken = new ArrayList<>(occupied.size());
        FORCE_UNLOCKING.add(wearer.getUUID());
        try {
            for (TrinketSlotAccess slot : occupied) {
                ItemStack stack = slot.get().copy();
                // 挣脱/解锁之后项圈就是普通物品了，不该继续带着锁
                CollarData.clearLock(stack);
                slot.set(ItemStack.EMPTY);
                taken.add(stack);
            }
        } finally {
            FORCE_UNLOCKING.remove(wearer.getUUID());
        }
        return taken;
    }

    /** 放进背包，满了就掉在脚下。 */
    private static void give(Player receiver, ItemStack stack) {
        if (!receiver.getInventory().add(stack)) {
            receiver.drop(stack, false, Prediction.SERVER_ONLY);
        }
    }
}
