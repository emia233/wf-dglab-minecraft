package com.civon.wildestfantasy.client;

import com.civon.wildestfantasy.WildestFantasy;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.controller.PeerState;
import com.civon.wildestfantasy.client.controller.RemoteState;
import com.civon.wildestfantasy.client.dglab.DglabAction;
import com.civon.wildestfantasy.client.dglab.DglabDevice;
import com.civon.wildestfantasy.client.dglab.DglabV4Client;
import com.civon.wildestfantasy.client.dglab.QrCodeService;
import com.civon.wildestfantasy.client.dglab.StrengthMapper;
import com.civon.wildestfantasy.client.net.ClientNetworking;
import com.civon.wildestfantasy.client.screen.ConfigScreen;
import com.civon.wildestfantasy.client.screen.ControllerScreen;
import com.civon.wildestfantasy.collar.CollarEscape;
import com.civon.wildestfantasy.collar.CollarService;
import com.civon.wildestfantasy.controller.ControllerData;
import com.civon.wildestfantasy.network.ControlActionPayload;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.civon.wildestfantasy.client.hud.EscapeBarHud;
import com.civon.wildestfantasy.client.hud.StrengthHud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * 客户端入口：持有全局 DG-LAB 连接，驱动每 tick 的强度映射，并提供 {@code /wildestfantasy} 命令。
 */
public class WildestFantasyClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/client");
    private static final DglabV4Client DGLAB = new DglabV4Client();
    private static final StrengthMapper MAPPER = new StrengthMapper();
    private static boolean autoConnectTried;

    /** 按键绑定界面里单独一栏「狂想」，三个键都归到这里。 */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(WildestFantasy.id("general"));

    /**
     * 打开郊狼控制面板的快捷键，可在原版「按键绑定」里改。
     *
     * <p>26.3 换成了 SDL 输入，硬编码 GLFW 键码会落到别的键上（86 曾被解释成小键盘减号），
     * 所以必须用 {@link InputConstants#KEY_V} 这类常量。
     */
    private static final KeyMapping KEY_PANEL = new KeyMapping(
            "key.wildestfantasy.panel",
            InputConstants.KEY_V,
            CATEGORY);

    /** 打开模组配置面板的快捷键（默认 B）。 */
    private static final KeyMapping KEY_CONFIG = new KeyMapping(
            "key.wildestfantasy.config",
            InputConstants.KEY_B,
            CATEGORY);

    /** 惩罚电击快捷键：默认不绑定，玩家自己在按键设置里指定。 */
    private static final KeyMapping KEY_PUNISH = new KeyMapping(
            "key.wildestfantasy.punish",
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    /** 供配置面板就地改键用。 */
    public static KeyMapping keyPanel() {
        return KEY_PANEL;
    }

    public static KeyMapping keyConfig() {
        return KEY_CONFIG;
    }

    public static KeyMapping keyPunish() {
        return KEY_PUNISH;
    }

    @Override
    public void onInitializeClient() {
        LOG.info("Wildest Fantasy 客户端初始化");

        KeyMappingHelper.registerKeyMapping(KEY_PANEL);
        KeyMappingHelper.registerKeyMapping(KEY_CONFIG);
        KeyMappingHelper.registerKeyMapping(KEY_PUNISH);
        ClientNetworking.register();
        registerControllerOpen();
        HudElementRegistry.addLast(WildestFantasy.id("strength_hud"), new StrengthHud());
        HudElementRegistry.addLast(WildestFantasy.id("escape_bar"), new EscapeBarHud());

        DGLAB.addStateListener(() -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                switch (DGLAB.state()) {
                    case WAITING_APP -> {
                        // 配对地址的交付交给「扫码连接」界面自己处理，这里不刷聊天栏
                    }
                    case READY -> {
                        WildestConfig cfg = WildestConfig.get();
                        chat(client, Component.literal("连接成功，绝对上限为 " + cfg.maxIntensity + "。")
                                .withStyle(ChatFormatting.GREEN));
                    }
                    case DISCONNECTED -> {
                        String err = DGLAB.lastError();
                        if (err != null) {
                            chat(client, Component.literal("连接断开: " + err)
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                    default -> {
                    }
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(MAPPER::tick);
        ClientTickEvents.END_CLIENT_TICK.register(WildestFantasyClient::tickAutoConnect);
        ClientTickEvents.END_CLIENT_TICK.register(WildestFantasyClient::tickEscapeHint);
        ClientTickEvents.END_CLIENT_TICK.register(WildestFantasyClient::tickRemote);
        ClientTickEvents.END_CLIENT_TICK.register(WildestFantasyClient::tickPanelKey);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(literal("dglab")
                    .then(literal("disconnect").executes(ctx -> {
                        DGLAB.disconnect();
                        feedback(ctx.getSource(), "已断开。");
                        return 1;
                    })));
        });
    }

    private static void tickAutoConnect(Minecraft client) {
        if (autoConnectTried || !WildestConfig.get().autoConnect) {
            return;
        }
        autoConnectTried = true;
        DGLAB.connect(WildestConfig.get().serverUrl);
        LOG.info("自动连接 {}", WildestConfig.get().serverUrl);
    }

    /** 进出世界时同步「自己的上限」给服务端；离开世界清掉主人下发的残留强度。 */
    private static boolean wasInWorld;
    /** 上次上报给服务端的设备连接状态（避免每 tick 刷包）。 */
    private static boolean reportedConnected;
    private static boolean reportedOnce;
    /** 上次上报给服务端的实时强度，用于去重。 */
    private static int reportedA = -1;
    private static int reportedB = -1;

    private static void tickRemote(Minecraft client) {
        boolean inWorld = client.player != null && client.level != null;
        if (inWorld && !wasInWorld) {
            ClientNetworking.reportLimits(WildestConfig.get());
            reportedConnected = !DGLAB.isReady();
            reportedOnce = false;
            reportedA = -1;
            reportedB = -1;
        } else if (!inWorld && wasInWorld) {
            RemoteState.reset();
            PeerState.reset();
            reportedOnce = false;
        }
        wasInWorld = inWorld;

        if (!inWorld) {
            return;
        }

        // 被控时把「郊狼设备是否连上」告诉服务端，主人面板据此显示连接状态。
        // 只在状态变化时发，避免每 tick 刷包。
        boolean connected = DGLAB.isReady();
        if (!reportedOnce || connected != reportedConnected) {
            ClientNetworking.reportSubjectStatus(connected);
            reportedConnected = connected;
            reportedOnce = true;
            // 断开时顺带把强度归零上报，主人 HUD 立刻变「未连接」
            if (!connected) {
                ClientNetworking.reportPeerStrength(0, 0);
                reportedA = 0;
                reportedB = 0;
            }
        }

        // 被控时把自己的实时强度报给服务端，主人的 HUD 才能看到「对方」。
        if (connected) {
            int a = DGLAB.lastSent(DglabAction.CHANNEL_A);
            int b = DGLAB.lastSent(DglabAction.CHANNEL_B);
            if (a != reportedA || b != reportedB) {
                ClientNetworking.reportPeerStrength(a, b);
                reportedA = a;
                reportedB = b;
            }
        }
    }

    /** 快捷键打开控制面板 / 配置面板，以及惩罚电击热键。 */
    private static void tickPanelKey(Minecraft client) {
        while (KEY_PANEL.consumeClick()) {
            LocalPlayer player = client.player;
            // 背包里一把控制器都没有就不开面板，直接说明原因（用户 2026-11-05 要求）
            if (player != null && !player.getInventory().contains(ControllerData::isController)) {
                chat(client, "你没有郊狼控制器。");
                continue;
            }
            ControllerScreen.open();
        }
        while (KEY_CONFIG.consumeClick()) {
            ConfigScreen.open();
        }
        while (KEY_PUNISH.consumeClick()) {
            // 惩罚电击：读面板上当前的强度与时长，直接发一次
            ClientNetworking.sendAction(ControlActionPayload.OP_PUNISH,
                    ControllerScreen.punishStrength(), ControllerScreen.punishMillis(), 0);
        }
    }

    /**
     * 手持郊狼控制器右键空气打开面板。
     *
     * <p>{@code UseItemCallback} 对着方块/实体点击也会触发，所以这里用 {@code client.hitResult}
     * 把「点到了东西」的情况排除掉 —— 那些场合要留给绑定逻辑。
     */
    private static void registerControllerOpen() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide()) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (!ControllerData.isController(stack)) {
                return InteractionResult.PASS;
            }
            Minecraft client = Minecraft.getInstance();
            if (client.hitResult != null && client.hitResult.getType() != HitResult.Type.MISS) {
                return InteractionResult.PASS;
            }
            ControllerScreen.open();
            return InteractionResult.SUCCESS;
        });
    }


    /** 客户端自己的挣脱计时，只用来画进度条；真正生效的是服务端那一份。 */
    private static int escapeTicks;
    private static boolean escapeShown;

    /** 进度条是否该显示（正按着潜行 + 跳跃、戴着没上锁的项圈）。 */
    public static boolean escapeBarVisible() {
        return escapeShown;
    }

    /** 0~1 的挣脱进度。 */
    public static float escapeBarProgress() {
        int total = Math.max(1, CollarEscape.REQUIRED_TICKS);
        return Math.min(1.0F, escapeTicks / (float) total);
    }

    private static void tickEscapeHint(Minecraft client) {
        LocalPlayer player = client.player;
        boolean wearing = player != null && CollarService.isWearingCollar(player);
        boolean locked = wearing && CollarService.isCollarLocked(player);
        boolean holding = client.options.keyShift.isDown() && client.options.keyJump.isDown();

        if (!wearing || locked || !holding) {
            escapeShown = false;
            escapeTicks = 0;
            return;
        }

        escapeTicks++;
        escapeShown = true;
    }


    private static void printStatus(FabricClientCommandSource source) {
        StringBuilder sb = new StringBuilder("DGLab: ").append(DGLAB.state());
        if (DGLAB.controllerId() != null) {
            sb.append(" | 控制方 ID ").append(DGLAB.controllerId());
        }
        if (DGLAB.appClientId() != null) {
            sb.append(" | APP ").append(DGLAB.appClientId());
        }
        sb.append(" | 设备 ").append(DGLAB.devices().size());
        sb.append(" | 当前输出 ").append(MAPPER.currentOutput());
        feedback(source, sb.toString());
        for (DglabDevice d : DGLAB.devices()) {
            feedback(source, "  - " + d.name() + " (" + d.type() + ", slot " + d.slotId() + ")");
        }
        if (DGLAB.lastError() != null) {
            feedback(source, "  最后错误: " + DGLAB.lastError());
        }
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        LOG.info(message);
        source.sendFeedback(Component.literal("[WildestFantasy] " + message));
    }

    private static void chat(Minecraft client, String message) {
        chat(client, Component.literal(message));
    }

    private static void chat(Minecraft client, Component message) {
        LOG.info(message.getString());
        if (client.player != null) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.literal("[WildestFantasy] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .append(message));
        }
    }

    public static DglabV4Client dglab() {
        return DGLAB;
    }

    /** 模组自己的配置目录（{@code config/wildestfantasy}）。 */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("wildestfantasy");
    }
}
