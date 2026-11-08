package com.civon.wildestfantasy.client.dglab;

/**
 * 一个被控方（DG-LAB APP）暴露出来的设备。
 *
 * @param slotId 设备槽位 ID，所有 device.op 指令都用它定位设备
 * @param name   展示名
 * @param type   设备类型
 */
public record DglabDevice(String slotId, String name, String type) {
}
