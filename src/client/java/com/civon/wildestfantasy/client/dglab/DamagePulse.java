package com.civon.wildestfantasy.client.dglab;

/**
 * 「受伤反馈」的伤害池：一次伤害会留下一段线性衰减的强度尾巴。
 *
 * <p>规则（用户 2026-11-06 定）：伤害结算后每 200ms 减掉<b>初始值的 10%</b>，
 * 10 步正好 2 秒归零；2 秒内再次受伤则<b>累加</b>，并从最后一次受伤重新计时。
 *
 * <p>为什么按时间算而不是每 tick 累减：tick 不保证 200ms 一次，
 * 直接按「距离上次受伤过了多少毫秒」算更准，也不怕掉帧。
 */
public final class DamagePulse {
    /** 每步 200ms。 */
    private static final long STEP_MS = 200L;
    /** 一共 10 步 = 2 秒。 */
    private static final int STEPS = 10;

    /** 当前这一轮衰减的起始强度。 */
    private static volatile double initial = 0.0;
    /** 最后一次受伤的时刻。 */
    private static volatile long lastHitAt = 0L;

    private DamagePulse() {
    }

    /**
     * 吃到一次伤害。
     *
     * @param damage 实际伤害（盔甲/效果算完之后的值）
     * @param factor 每点伤害折算成多少强度（配置里的「伤害反馈」）
     */
    public static void add(float damage, double factor) {
        if (damage <= 0.0F || factor <= 0.0) {
            return;
        }
        long now = System.currentTimeMillis();
        // 先把上一轮剩下的算进来，再叠新的 —— 否则旧尾巴会被新伤害「复活」成满值
        double current = value(now);
        initial = current + damage * factor;
        lastHitAt = now;
    }

    /** 当前应该输出多少强度。 */
    public static double value(long now) {
        double start = initial;
        if (start <= 0.0) {
            return 0.0;
        }
        double steps = (now - lastHitAt) / (double) STEP_MS;
        double remain = 1.0 - steps / STEPS;
        return remain <= 0.0 ? 0.0 : start * remain;
    }

    /** 离开世界 / 断线时清空。 */
    public static void reset() {
        initial = 0.0;
        lastHitAt = 0L;
    }
}
