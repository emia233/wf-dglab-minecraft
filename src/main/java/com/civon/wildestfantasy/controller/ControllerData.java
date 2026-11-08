package com.civon.wildestfantasy.controller;

import com.civon.wildestfantasy.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/**
 * 郊狼控制器物品上的绑定信息：它现在控制的是谁。
 *
 * <p>主人一侧的记忆存在物品 NBT（换控制器要重新绑），被控一侧的记忆存在
 * <b>项圈</b> 的 {@code Owner} 字段（{@link com.civon.wildestfantasy.collar.CollarData}）。
 * 两边都记着才认为关系成立，任何一边丢了都会自动失效。
 */
public final class ControllerData {
    private static final String KEY_SUBJECT = "Subject";
    private static final String KEY_SUBJECT_NAME = "SubjectName";

    private ControllerData() {
    }

    public static boolean isController(ItemStack stack) {
        return stack.is(ModItems.CONTROLLER);
    }

    public static Optional<UUID> getSubject(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        String raw = data.copyTag().getStringOr(KEY_SUBJECT, "");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String getSubjectName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(KEY_SUBJECT_NAME, "");
    }

    public static void bind(ItemStack stack, UUID subject, String name) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(KEY_SUBJECT, subject.toString());
            tag.putString(KEY_SUBJECT_NAME, name);
        });
    }

    public static void unbind(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(KEY_SUBJECT);
            tag.remove(KEY_SUBJECT_NAME);
        });
    }
}
