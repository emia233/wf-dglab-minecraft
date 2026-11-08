package com.civon.wildestfantasy;

import com.civon.wildestfantasy.collar.CollarEscape;
import com.civon.wildestfantasy.collar.CollarEvents;
import com.civon.wildestfantasy.controller.ControllerEvents;
import com.civon.wildestfantasy.feedback.DamageFeedbackEvents;
import com.civon.wildestfantasy.network.WildestNetworking;
import com.civon.wildestfantasy.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wildest Fantasy —— 把 Minecraft 的战斗反馈送到 DG-LAB 郊狼 3.0。
 *
 * <p>模组是双端的：单人模式下服务端就是集成服务器；联机时服务端负责校验
 * 「谁是主人的被控者」，真正的郊狼通信永远发生在<b>被控者自己的客户端</b>。
 */
public class WildestFantasy implements ModInitializer {
    public static final String MOD_ID = "wildestfantasy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModItems.register();
        WildestNetworking.registerCommon();
        CollarEvents.register();
        CollarEscape.register();
        ControllerEvents.register();
        DamageFeedbackEvents.register();
        LOGGER.info("Wildest Fantasy 已加载（双端模组）");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
