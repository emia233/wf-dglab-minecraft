package com.civon.wildestfantasy.collar;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/**
 * 项圈物品上的附加数据：是否被项圈锁锁死、主人是谁。
 *
 * <p>数据直接挂在 {@link ItemStack} 的自定义 NBT 上，跟着物品走 ——
 * 这样项圈被摘下、放进背包、甚至被死亡掉落逻辑处理时，锁定状态和主人关系都不会丢。
 */
public final class CollarData {
    private static final String KEY_LOCKED = "Locked";
    private static final String KEY_OWNER = "Owner";

    private CollarData() {
    }

    /** 这个项圈是否被项圈锁锁死了。 */
    public static boolean isLocked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(KEY_LOCKED, false);
    }

    public static void setLocked(ItemStack stack, boolean locked) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(KEY_LOCKED, locked));
    }

    /** 主人的 UUID（给这个玩家戴上项圈的人）。 */
    public static Optional<UUID> getOwner(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        String raw = data.copyTag().getStringOr(KEY_OWNER, "");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static void setOwner(ItemStack stack, UUID owner) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KEY_OWNER, owner.toString()));
    }

    /** 清理锁定状态（死亡解锁时用）。 */
    public static void clearLock(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(KEY_LOCKED));
    }
}
