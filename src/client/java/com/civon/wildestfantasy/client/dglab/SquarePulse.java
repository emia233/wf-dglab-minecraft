package com.civon.wildestfantasy.client.dglab;

/**
 * 方波式的强度叠加：触发后瞬间升到目标值，保持固定时长，然后瞬间归零。
 *
 * <p>用在「弹射物打中」「摔落」这类一次性事件上 —— 用户要的是干脆的「痛一下」，
 * 不像伤害反馈那样花 2 秒慢慢衰减。
 *
 * <p>短时间连续触发时取较大值、结束时间顺延，避免第二次摔倒把强度反而压低。
 */
public final class SquarePulse {
    private int value;
    private long untilMs;

    /** 触发一次，保持 {@code durationMs} 毫秒。 */
    public void hold(int magnitude, long durationMs, long now) {
        if (magnitude <= 0 || durationMs <= 0) {
            return;
        }
        if (magnitude > this.value) {
            this.value = magnitude;
        }
        this.untilMs = now + durationMs;
    }

    /** 当前该叠加多少。 */
    public int value(long now) {
        return now < untilMs ? value : 0;
    }

    public void reset() {
        this.value = 0;
        this.untilMs = 0L;
    }
}
