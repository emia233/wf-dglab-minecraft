package com.civon.wildestfantasy.client.dglab;

/**
 * 两个「一次性事件」的方波强度：弹射物命中与摔落。
 *
 * <p>都是瞬间升、保持 {@link #HOLD_MS} 毫秒、瞬间归零，和伤害反馈那种 2 秒缓慢衰减区分开。
 * 两路各自独立累加，最后一起并进总输出。
 */
public final class FeedbackPulses {
    /** 方波保持时长（毫秒）。 */
    public static final long HOLD_MS = 300L;

    private static final SquarePulse PROJECTILE = new SquarePulse();
    private static final SquarePulse FALL = new SquarePulse();

    private FeedbackPulses() {
    }

    /** 被有伤害的弹射物打中。 */
    public static void projectile(int strength, long now) {
        PROJECTILE.hold(strength, HOLD_MS, now);
    }

    /** 摔落着地。 */
    public static void fall(int strength, long now) {
        FALL.hold(strength, HOLD_MS, now);
    }

    /** 当前应该额外叠加的强度（两路相加）。 */
    public static int value(long now) {
        return PROJECTILE.value(now) + FALL.value(now);
    }

    public static void reset() {
        PROJECTILE.reset();
        FALL.reset();
    }
}
