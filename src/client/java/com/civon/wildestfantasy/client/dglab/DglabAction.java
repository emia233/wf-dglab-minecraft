package com.civon.wildestfantasy.client.dglab;

/**
 * DG-LAB SOCKET V4 的 device.op 动作类型与通道编号。
 *
 * <p>数值来自 dglab-kit 的 {@code V4ActionType} / {@code V4Channel}：
 * <ul>
 *   <li>0 = AppendPulseData（裸波形，本模组不使用）</li>
 *   <li>3 = AddIntensity（相对增减）</li>
 *   <li>4 = SetTempIntensity（临时强度，到期回落，本模组主用）</li>
 *   <li>7 = SetIntensity（绝对强度）</li>
 * </ul>
 */
public final class DglabAction {
    public static final int APPEND_PULSE = 0;
    public static final int ADD_INTENSITY = 3;
    public static final int SET_TEMP_INTENSITY = 4;
    public static final int SET_INTENSITY = 7;

    public static final int CHANNEL_A = 0;
    public static final int CHANNEL_B = 1;

    /** 协议层允许的强度上限（APP 端还可能设置更低的通道上限）。 */
    public static final int MAX_INTENSITY = 200;

    private DglabAction() {
    }
}
