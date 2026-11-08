package com.civon.wildestfantasy.registry;

import com.civon.wildestfantasy.WildestFantasy;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/**
 * 模组的物品注册表。
 *
 * <p>项圈本身不是装备（不占胸甲槽），而是通过 Trinkets 的 {@code chest/necklace}
 * 饰品槽佩戴 —— 那个槽和胸甲完全独立，所以可以「胸甲 + 项圈」同时穿戴。
 */
public final class ModItems {
    private ModItems() {
    }

    public static final ResourceKey<Item> COLLAR_ID =
            ResourceKey.create(Registries.ITEM, WildestFantasy.id("collar"));
    public static final Item COLLAR =
            new Item(new Item.Properties().setId(COLLAR_ID).stacksTo(1));

    public static final ResourceKey<Item> COLLAR_KEY_ID =
            ResourceKey.create(Registries.ITEM, WildestFantasy.id("collar_key"));
    public static final Item COLLAR_KEY =
            new Item(new Item.Properties().setId(COLLAR_KEY_ID).stacksTo(1));

    public static final ResourceKey<Item> COLLAR_LOCK_ID =
            ResourceKey.create(Registries.ITEM, WildestFantasy.id("collar_lock"));
    public static final Item COLLAR_LOCK =
            new Item(new Item.Properties().setId(COLLAR_LOCK_ID).stacksTo(16));

    /** 郊狼控制器：主人手持它右键戴项圈的玩家来建立控制关系，右键空气打开控制面板。 */
    public static final ResourceKey<Item> CONTROLLER_ID =
            ResourceKey.create(Registries.ITEM, WildestFantasy.id("dglabcontroller"));
    public static final Item CONTROLLER =
            new Item(new Item.Properties().setId(CONTROLLER_ID).stacksTo(1));

    /** 创造模式「红石」分区。 */
    private static final ResourceKey<CreativeModeTab> REDSTONE_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.withDefaultNamespace("redstone_blocks"));

    public static void register() {
        Registry.register(BuiltInRegistries.ITEM, COLLAR_ID, COLLAR);
        Registry.register(BuiltInRegistries.ITEM, COLLAR_KEY_ID, COLLAR_KEY);
        Registry.register(BuiltInRegistries.ITEM, COLLAR_LOCK_ID, COLLAR_LOCK);
        Registry.register(BuiltInRegistries.ITEM, CONTROLLER_ID, CONTROLLER);

        CreativeModeTabEvents.modifyOutputEvent(REDSTONE_TAB).register(output -> {
            output.accept(COLLAR);
            output.accept(COLLAR_KEY);
            output.accept(COLLAR_LOCK);
            output.accept(CONTROLLER);
        });

        WildestFantasy.LOGGER.info("已注册物品：collar、collar_key、collar_lock、dglabcontroller（郊狼控制器）");
    }
}
