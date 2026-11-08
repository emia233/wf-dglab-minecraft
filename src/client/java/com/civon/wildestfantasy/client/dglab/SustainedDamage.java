package com.civon.wildestfantasy.client.dglab;

/**
 * 持续伤害反馈的「软起软落」包络。
 *
 * <p>用户要求：着火/中毒这类状态出现时，不要瞬间跳到目标值，而是用 2 秒线性爬上去；
 * 状态消失后再用 2 秒线性落回 0。这样听感和体感都更自然。
 *
 * <p>实现很简单：记住上一段的起点值与起点时间，任何时刻取「起点 → 目标」的线性插值，
 * 中途目标再次变化就以当前插值结果作为新的起点，所以连续切换也很平滑。
 */
public final class SustainedDamage {
    /** 上升与下降各自用多久（毫秒）。 */
    private static final long RAMP_MS = 2000L;

    private float from;
    private float target;
    private long startMs;

    /**
     * 设定目标强度。
     *
     * @param dest 目标值（0 表示「现在没有持续伤害」）
     * @param now  当前毫秒时间
     */
    public void setTarget(float dest, long now) {
        if (dest == target) {
            return;
        }
        // 以当前显示值作为新一段的起点，避免中途改目标时跳变
        from = value(now);
        target = dest;
        startMs = now;
    }

    /** 当前应该输出的强度。 */
    public float value(long now) {
        long elapsed = now - startMs;
        if (elapsed >= RAMP_MS) {
            return target;
        }
        if (elapsed <= 0) {
            return from;
        }
        float t = elapsed / (float) RAMP_MS;
        return from + (target - from) * t;
    }

    /** 直接归零（死亡、脱项圈、切创造等场合用，不留过渡尾巴）。 */
    public void reset() {
        from = 0.0F;
        target = 0.0F;
        startMs = 0L;
    }
}
