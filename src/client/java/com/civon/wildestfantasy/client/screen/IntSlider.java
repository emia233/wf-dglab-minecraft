package com.civon.wildestfantasy.client.screen;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 原版风格的整数滑块。
 *
 * <p>{@link AbstractSliderButton} 内部只认 0~1 的 double，这里把它映射成整数区间，
 * 并按 {@code step} 对齐，因此外观、拖动手感、键盘操作都和原版选项里的滑块一致。
 */
public final class IntSlider extends AbstractSliderButton {
    private final String label;
    private final String unit;
    private final int min;
    private int max;
    private final int step;
    private final IntConsumer onChange;

    public IntSlider(int x, int y, int width, int height, String label,
                     int min, int max, int initial, int step, IntConsumer onChange) {
        this(x, y, width, height, label, min, max, initial, step, "", onChange);
    }

    public IntSlider(int x, int y, int width, int height, String label,
                     int min, int max, int initial, int step, String unit, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), toRatio(initial, min, max));
        this.label = label;
        this.unit = unit == null ? "" : unit;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.onChange = onChange;
        updateMessage();
    }

    /** 当前整数取值。 */
    public int intValue() {
        int raw = min + (int) Math.round(this.value * (max - min));
        if (step > 1) {
            raw = min + Math.round((raw - min) / (float) step) * step;
        }
        return Math.max(min, Math.min(max, raw));
    }

    /** 外部改值（用于两个滑块联动），不会触发 onChange。 */
    public void setIntValue(int v) {
        this.value = toRatio(Math.max(min, Math.min(max, v)), min, max);
        updateMessage();
    }

    /**
     * 调整上限（用于「绝对上限」变小时把受它约束的滑块一起收窄）。
     *
     * <p>不重建控件，所以正在拖动时也不会中断；当前值若超出新上限会当场回缩。
     */
    public void setMax(int newMax) {
        int clampedMax = Math.max(min + 1, newMax);
        if (clampedMax == max) {
            return;
        }
        int current = Math.min(intValue(), clampedMax);
        this.max = clampedMax;
        setIntValue(current);
    }

    private static double toRatio(int v, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) (v - min) / (max - min)));
    }

    @Override
    protected void updateMessage() {
        int v = intValue();
        String range = v + unit + " / " + max + unit;
        setMessage(Component.literal(label.isEmpty() ? range : label + " " + range));
    }

    @Override
    protected void applyValue() {
        onChange.accept(intValue());
    }
}
