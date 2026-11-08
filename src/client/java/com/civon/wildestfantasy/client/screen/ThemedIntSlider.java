package com.civon.wildestfantasy.client.screen;

import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 郊狼控制面板专用的整数滑块：外观由美术贴图决定，取值逻辑与 {@link IntSlider} 一致。
 */
public final class ThemedIntSlider extends ThemedSlider {
    private final String label;
    private final String unit;
    private final int min;
    private final int max;
    private final int step;
    private final IntConsumer onChange;

    public ThemedIntSlider(int x, int y, int width, int height, String label,
                           int min, int max, int initial, int step, IntConsumer onChange) {
        this(x, y, width, height, label, min, max, initial, step, "", onChange);
    }

    public ThemedIntSlider(int x, int y, int width, int height, String label,
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

    public int intValue() {
        int raw = min + (int) Math.round(this.value * (max - min));
        if (step > 1) {
            raw = min + Math.round((raw - min) / (float) step) * step;
        }
        return Math.max(min, Math.min(max, raw));
    }

    public void setIntValue(int v) {
        this.value = toRatio(Math.max(min, Math.min(max, v)), min, max);
        updateMessage();
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
