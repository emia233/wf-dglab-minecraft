package com.civon.wildestfantasy.client.controller;

/**
 * 主人视角下「对方」（被控者）的实时强度。
 *
 * <p>数据来自服务端转发的 {@code PeerStrengthPayload}：被控者客户端上报自己
 * 实际下发到 A / B 的数值，服务端再转给主人，所以主人自己连不连郊狼都能看到。
 *
 * <p>值为 {@code -1} 表示对方没连郊狼或还没有数据，HUD 显示「未连接」。
 */
public final class PeerState {
    private static volatile int a = -1;
    private static volatile int b = -1;

    private PeerState() {
    }

    public static void set(int newA, int newB) {
        a = newA;
        b = newB;
    }

    public static void reset() {
        a = -1;
        b = -1;
    }

    /** 有没有对方的真实数据。 */
    public static boolean known() {
        return a >= 0 || b >= 0;
    }

    public static int a() {
        return Math.max(0, a);
    }

    public static int b() {
        return Math.max(0, b);
    }
}
