package com.civon.wildestfantasy.collar;

import com.civon.wildestfantasy.WildestFantasy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「潜行 + 跳跃按住 5 秒挣脱项圈」的服务端权威计时。
 *
 * <p>跳跃和潜行的按键状态在 26.3 已经由 {@link ServerPlayer#getLastClientInput()} 同步到服务端，
 * 所以这里不需要额外的网络包 —— 服务端自己就能判定。
 *
 * <p>客户端另有一份独立计时，只用来在动作条上画倒计时进度；真正生效的是这一份。
 */
public final class CollarEscape {
    /** 临时调试开关：打开后每 20 tick 打印服务端看到的按键状态。 */
    private static final boolean DEBUG = false;
    /** 5 秒 = 100 tick。 */
    public static final int REQUIRED_TICKS = 100;
    /** 玩家连续按住的 tick 数。 */
    private static final Map<UUID, Integer> PROGRESS = new HashMap<>();

    private CollarEscape() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                tick(player);
            }
        });
    }

    /** 已按住的 tick 数（给客户端/调试读）。 */
    public static int progress(ServerPlayer player) {
        return PROGRESS.getOrDefault(player.getUUID(), 0);
    }

    public static void reset(ServerPlayer player) {
        PROGRESS.remove(player.getUUID());
    }

    private static void tick(ServerPlayer player) {
        UUID id = player.getUUID();

        // 没戴项圈、或者被锁死、或者已经死了：都不算挣脱
        if (!CollarService.isWearingCollar(player)
                || CollarService.isCollarLocked(player)
                || !player.isAlive()) {
            PROGRESS.remove(id);
            return;
        }

        Input input = player.getLastClientInput();
        boolean shift = input != null && input.shift();
        boolean jump = input != null && input.jump();
        boolean holding = shift && jump;

        // 临时诊断：戴着项圈时每 20 tick 报一次服务端看到的按键状态
        if (DEBUG && player.tickCount % 20 == 0) {
            WildestFantasy.LOGGER.info("escape-debug: shift={} jump={} ticks={}",
                    shift, jump, PROGRESS.getOrDefault(id, 0));
        }

        if (!holding) {
            PROGRESS.remove(id);
            return;
        }

        int ticks = PROGRESS.merge(id, 1, Integer::sum);
        if (ticks >= REQUIRED_TICKS) {
            PROGRESS.remove(id);
            if (CollarService.removeCollar(player)) {
                player.sendSystemMessage(Component.literal("§a你挣脱了项圈。"));
            }
        }
    }
}
