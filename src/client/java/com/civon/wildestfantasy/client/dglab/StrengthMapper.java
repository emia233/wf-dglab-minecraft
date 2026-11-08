package com.civon.wildestfantasy.client.dglab;

import com.civon.wildestfantasy.client.WildestFantasyClient;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.controller.RemoteState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;

/**
 * 把游戏内的状态换算成郊狼强度。
 *
 * <p>设计取舍：模组只发送“强度”，不发送任何波形。APP 里玩家自己挑选波形并点开始，
 * 模组只负责把强度推上去／放下来，所以波形手感始终由玩家自己掌控。
 *
 * <p>下发方式是“心跳式临时强度”：每 {@link #REFRESH_INTERVAL_MS} 毫秒用
 * {@code SetTempIntensity} 刷新一次，任务寿命 {@link #TASK_LIFETIME_MS} 毫秒。
 * 这样一旦游戏崩溃、退出或连接断开，最多 1.5 秒后主机会自动回落到原强度，
 * 不会出现“卡在最高强度”的危险状态。
 *
 * <p>强度来源（全部相加，最后夹到绝对上限）：
 * <ol>
 *   <li><b>主人下发的</b>：基础强度与惩罚电击取较大者；</li>
 *   <li><b>血量反馈</b>：缺失的生命值 × 系数（持续）；</li>
 *   <li><b>伤害反馈</b>：实际受到的伤害 × 系数（2 秒线性衰减，见 {@link DamagePulse}）；</li>
 *   <li><b>死亡脉冲</b>：死亡瞬间固定强度，持续 deathHoldMs，与何时复活无关。</li>
 * </ol>
 *
 * <p>“紧急避险”：只有生存模式和冒险模式才会输出强度。切到创造或旁观时立即归零，
 * 切回生存／冒险后再按当前血量恢复输出。
 */
public final class StrengthMapper {
    private static final int REFRESH_INTERVAL_MS = 200;
    private static final int TASK_LIFETIME_MS = 1500;

    private boolean wasDead;
    /** 死亡脉冲的截止时刻。 */
    private long deathUntilMs;
    private long lastSendAtMs;
    private int lastApplied = -1;
    /** 持续伤害反馈的 2 秒上升 / 2 秒下降包络。 */
    private final SustainedDamage sustained = new SustainedDamage();

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            reset();
            return;
        }

        WildestConfig cfg = WildestConfig.get();
        DglabV4Client dglab = WildestFantasyClient.dglab();

        float health = player.getHealth();
        float maxHealth = Math.max(1.0f, player.getMaxHealth());
        boolean dead = player.isDeadOrDying() || health <= 0.0f;
        // 紧急避险：只有生存 / 冒险模式才输出强度，创造与旁观一律立即归零。
        boolean canOutput = !player.isCreative() && !player.isSpectator();

        if (!cfg.enabled) {
            if (dglab.isReady()) {
                push(dglab, cfg, 0, System.currentTimeMillis());
            }
            wasDead = dead;
            return;
        }

        if (!dglab.isReady()) {
            wasDead = dead;
            return;
        }

        long now = System.currentTimeMillis();

        // 单人暂停时游戏时间是停的，电击也一并停掉；继续游戏自然恢复。
        // 联机时 Esc 不会暂停（isPaused() 为 false），所以这一段对多人没有影响。
        if (client.isPaused()) {
            push(dglab, cfg, 0, now);
            return;
        }

        // 死亡：固定输出「死亡强度」deathHoldMs 毫秒，与玩家何时点复活无关。
        // 注意这里不能走血量反馈——死亡时血量是 0，会被算成「缺了 20 点血」而一直顶着上限。
        if (dead) {
            if (!wasDead) {
                wasDead = true;
                deathUntilMs = cfg.deathEnabled ? now + cfg.deathHoldMs : 0L;
            }
            sustained.reset();
            // 死亡瞬间残留的伤害脉冲没有意义，直接丢掉，免得复活后被它叠一下
            DamagePulse.reset();
            FeedbackPulses.reset();
            // 主人关掉「受伤输出强度」后，死亡也不再额外加输出，只剩主人下发的强度
            int death = RemoteState.hpEnabled() && now < deathUntilMs ? cfg.deathIntensity : 0;
            push(dglab, cfg, canOutput ? cfg.clamp(death + masterOutput()) : 0, now);
            return;
        }

        // 死亡脉冲可能比「躺着」的那段时间更长：玩家一死就立刻点重生时，
        // 这里要把剩余时长继续播完，否则满血重算会把死亡强度整个盖掉，完全感受不到。
        if (now < deathUntilMs) {
            wasDead = false;
            sustained.reset();
            int death = RemoteState.hpEnabled() ? cfg.deathIntensity : 0;
            push(dglab, cfg, canOutput ? cfg.clamp(death + masterOutput()) : 0, now);
            return;
        }

        // 从死亡状态复活：清掉残留
        if (wasDead) {
            wasDead = false;
            deathUntilMs = 0L;
            DamagePulse.reset();
            sustained.reset();
            push(dglab, cfg, canOutput ? computeTarget(cfg, player, health, maxHealth, now) : 0, now);
            return;
        }

        // 紧急避险：创造 / 旁观期间不输出
        if (!canOutput) {
            sustained.reset();
            push(dglab, cfg, 0, now);
            return;
        }

        push(dglab, cfg, computeTarget(cfg, player, health, maxHealth, now), now);
    }

    /**
     * 当前该输出多少强度。
     *
     * <p>主人可以在面板上关掉被控者的「受伤输出强度」，关掉后血量反馈、伤害反馈与持续伤害反馈
     * 同时归零，输出只剩主人下发的强度。
     */
    private int computeTarget(WildestConfig cfg, LocalPlayer player, float health, float maxHealth, long now) {
        int total = masterOutput();
        if (RemoteState.hpEnabled()) {
            // 血量反馈：缺得越多越强（持续）
            float missing = Math.max(0.0f, maxHealth - Math.max(0.0f, health));
            int fromHp = (int) Math.round(missing * cfg.hpFeedback);
            // 伤害反馈：实际挨的伤害，2 秒内线性衰减
            int fromDamage = (int) Math.round(DamagePulse.value(now));
            // 持续伤害反馈：状态在就输出一份固定强度，多个状态不叠加（只取一份）
            float sustainedTarget = cfg.sustainedFeedback > 0 && isTakingSustainedDamage(player)
                    ? cfg.sustainedFeedback
                    : 0.0f;
            sustained.setTarget(sustainedTarget, now);
            int fromSustained = Math.round(sustained.value(now));
            // 弹射物 / 摔落：300ms 方波，瞬间升降
            int fromPulses = FeedbackPulses.value(now);
            total += fromHp + fromDamage + fromSustained + fromPulses;
        } else {
            sustained.setTarget(0.0f, now);
            FeedbackPulses.reset();
        }
        return cfg.clamp(total);
    }

    /**
     * 身上是否带着「会持续掉血」的状态。
     *
     * <p>同时有多个也只算一份（用户要求：中毒 + 火烧仍然是同一份强度）。
     *
     * <p>抗火状态下着火、岩浆、岩浆块都不会真的掉血，因此这些情况不算。
     */
    private static boolean isTakingSustainedDamage(LocalPlayer player) {
        boolean fireResistant = player.hasEffect(MobEffects.FIRE_RESISTANCE);
        if (!fireResistant) {
            if (player.isOnFire() || player.isInLava()) {
                return true;
            }
            if (player.getBlockStateOn().is(Blocks.MAGMA_BLOCK)) {
                return true;
            }
        }
        // 仙人掌：贴上去就持续掉血，和岩浆一样属于「踩到就痛」，抗火不影响
        if (player.getBlockStateOn().is(Blocks.CACTUS)) {
            return true;
        }
        if (player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WITHER)) {
            return true;
        }
        // 溺水：已经在憋气（氧气见底）才开始扣血
        if (player.isUnderWater() && player.getAirSupply() <= 0) {
            return true;
        }
        // 细雪冻伤：冻结进度一旦开始涨就会持续掉血
        if (player.getTicksFrozen() > 0) {
            return true;
        }
        // 饥饿：饥饿值归零后开始饿死
        return player.getFoodData().getFoodLevel() <= 0;
    }

    /** 主人下发的强度：持续基础强度与惩罚电击取较大者。 */
    private int masterOutput() {
        return Math.max(RemoteState.base(), RemoteState.punishNow());
    }

    /** 按刷新节奏下发，避免 60fps 每帧都发一条。 */
    private void push(DglabV4Client dglab, WildestConfig cfg, int target, long now) {
        int v = cfg.clamp(target);
        if (v != lastApplied || now - lastSendAtMs >= TASK_LIFETIME_MS / 2) {
            apply(dglab, cfg, v);
        }
    }

    private void apply(DglabV4Client dglab, WildestConfig cfg, int value) {
        DglabDevice device = dglab.primaryDevice();
        if (device == null) {
            return;
        }
        int v = cfg.clamp(value);
        dglab.setTempIntensity(device.slotId(), DglabAction.CHANNEL_A, v, TASK_LIFETIME_MS);
        if (cfg.syncChannels) {
            dglab.setTempIntensity(device.slotId(), DglabAction.CHANNEL_B, v, TASK_LIFETIME_MS);
        }
        lastApplied = v;
        lastSendAtMs = System.currentTimeMillis();
        dglab.markSent(DglabAction.CHANNEL_A, v);
        if (cfg.syncChannels) {
            dglab.markSent(DglabAction.CHANNEL_B, v);
        }
    }

    public void reset() {
        wasDead = false;
        deathUntilMs = 0L;
        lastApplied = -1;
        lastSendAtMs = 0;
        DamagePulse.reset();
        sustained.reset();
        FeedbackPulses.reset();
    }

    /** 供 HUD 显示“现在正在输出多少”。 */
    public int currentOutput() {
        return Math.max(0, lastApplied);
    }
}
