package com.civon.wildestfantasy.client.controller;

/**
 * 被控者客户端保存的「主人下发的状态」。
 *
 * <p>这些值只存在于本地内存：断线、退出世界、或者收到 CLEAR 时都会被清掉，
 * 因此不会出现「上次被电的强度一直挂着」的情况。
 */
public final class RemoteState {
    private static volatile int base = 0;
    private static volatile int punish = 0;
    private static volatile long punishUntilMs = 0L;
    private static volatile boolean bound = false;
    /** 血量映射开关：由主人控制，默认开启。 */
    private static volatile boolean hpEnabled = true;

    private RemoteState() {
    }

    public static boolean hpEnabled() {
        return hpEnabled;
    }

    public static void setHpEnabled(boolean value) {
        hpEnabled = value;
    }

    public static int base() {
        return base;
    }

    public static void setBase(int value) {
        base = Math.max(0, value);
        bound = true;
    }

    public static void punish(int value, int millis) {
        punish = Math.max(0, value);
        punishUntilMs = System.currentTimeMillis() + Math.max(0, millis);
    }

    /** 惩罚电击当前的剩余强度（过期即 0）。 */
    public static int punishNow() {
        return System.currentTimeMillis() < punishUntilMs ? punish : 0;
    }

    public static boolean isBound() {
        return bound;
    }

    /** 收到 CLEAR：主人要求停止持续输出。 */
    public static void clear() {
        base = 0;
        punish = 0;
        punishUntilMs = 0L;
        bound = false;
    }

    /** 断开连接/离开世界时调用。 */
    public static void reset() {
        clear();
        hpEnabled = true;
    }
}
