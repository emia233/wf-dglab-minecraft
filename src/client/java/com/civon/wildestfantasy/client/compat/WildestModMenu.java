package com.civon.wildestfantasy.client.compat;

import com.civon.wildestfantasy.client.screen.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：在模组列表里点齿轮直接打开模组配置界面。
 *
 * <p>这里是<b>编译期</b>依赖（{@code compileOnly}），玩家不装 Mod Menu 也能正常进游戏 ——
 * Fabric 只在装了 Mod Menu 时才读 {@code modmenu} 入口点。
 */
public final class WildestModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new ConfigScreen();
    }
}
