package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.client.net.ClientNetworking;
import com.civon.wildestfantasy.network.ControlActionPayload;
import com.civon.wildestfantasy.network.PanelStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

/**
 * 主人的控制面板。
 *
 * <p>控件全部换成美术提供的贴图版（{@link ThemedButton}/{@link ThemedIntSlider}），
 * 模组配置界面则继续用原版控件。
 *
 * <p>所有「调数值」都是滑块，「触发」才是按钮。效果区把 8 种效果从上到下平铺，
 * 超出可视区时用鼠标滚轮滚动（右侧画原版风格滚动条）。
 */
public final class ControllerScreen extends Screen {
    /** 与 {@code ServerController.EFFECT_NAMES} 顺序一致。 */
    private static final String[] EFFECT_NAMES = {
            "缓慢", "失明", "虚弱", "饥饿", "漂浮", "缓降", "生命回复", "抗性提升"
    };
    /** 等级固定：前六种 I 级，后两种 II 级。 */
    private static final int[] EFFECT_AMPLIFIER = {0, 0, 0, 0, 0, 0, 1, 1};

    private static final int PANEL_WIDTH = 420;
    private static final int ROW_H = 24;
    /** 惩罚电击时长的取值范围。 */
    private static final int PUNISH_MS_MIN = 100;
    private static final int PUNISH_MS_MAX = 5000;
    private static final int PUNISH_MS_STEP = 100;

    private int baseStrength;
    /** 惩罚电击参数做成静态：热键触发的电击要复用面板上最后一次设的值。 */
    private static int punishStrength = 50;
    private static int punishMillis = 800;
    private final int[] effectSeconds = new int[EFFECT_NAMES.length];

    private int scrollOffset;
    private int maxScroll;
    private int effectTop;
    private int effectBottom;

    private int lastValid = -1;
    private int comfortMax = 40;
    private int absoluteMax = 200;
    private int serverBase;
    private int hpEnabled = 1;
    private int statusX;
    private int statusY;

    public ControllerScreen() {
        super(Component.literal("郊狼控制面板"));
        Arrays.fill(effectSeconds, 10);
    }

    /** 从任意线程/任意位置安全地打开面板。 */
    public static void open() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new ControllerScreen()));
    }

    public static int punishStrength() {
        return punishStrength;
    }

    public static int punishMillis() {
        return punishMillis;
    }

    @Override
    protected void init() {
        ClientNetworking.sendAction(ControlActionPayload.OP_QUERY, 0, 0, 0);
        PanelStatePayload panel = ClientNetworking.panel();
        lastValid = panel.valid();
        comfortMax = panel.comfortMax();
        absoluteMax = panel.absoluteMax();
        serverBase = panel.baseIntensity();
        hpEnabled = panel.hpEnabled();
        if (serverBase > 0 && baseStrength == 0) {
            baseStrength = serverBase;
        }
        rebuild();
    }

    @Override
    public void tick() {
        PanelStatePayload panel = ClientNetworking.panel();
        if (panel.valid() != lastValid
                || panel.comfortMax() != comfortMax
                || panel.absoluteMax() != absoluteMax
                || panel.baseIntensity() != serverBase
                || panel.hpEnabled() != hpEnabled) {
            init();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // 按整行滚动：滚动量对齐到行高，避免出现「半行卡在边界」的割裂感
        int step = scrollY > 0 ? -ROW_H : scrollY < 0 ? ROW_H : 0;
        if (step != 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + step));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void rebuild() {
        clearWidgets();
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int y = 52;

        // 第 0 行：绑定状态只是文字（画在 extractRenderState 里），右侧才是「绑定自己」
        statusX = left;
        statusY = y + 6;
        addRenderableWidget(new ThemedButton(left + PANEL_WIDTH - 92, y, 92, 20,
                Component.literal("绑定自己"),
                () -> ClientNetworking.sendAction(ControlActionPayload.OP_BIND_SELF, 0, 0, 0)));
        y += ROW_H;

        // 第 1 行：基础强度（不得超过被控者的舒适上限）+ 应用 + 停
        int baseLimit = Math.max(0, Math.min(comfortMax, absoluteMax));
        if (baseStrength > baseLimit) {
            baseStrength = baseLimit;
        }
        addRenderableWidget(new ThemedIntSlider(left, y, 200, 20, "基础强度", 0, baseLimit,
                baseStrength, 1, v -> baseStrength = v));
        addRenderableWidget(new ThemedButton(left + 204, y, 76, 20, Component.literal("应用"),
                () -> ClientNetworking.sendAction(ControlActionPayload.OP_BASE, baseStrength, 0, 0)));
        // 「停」只停基础强度：把基础强度设成 0，惩罚电击该响还响
        addRenderableWidget(new ThemedButton(left + 284, y, 56, 20, Component.literal("停"),
                () -> ClientNetworking.sendAction(ControlActionPayload.OP_BASE, 0, 0, 0)));
        y += ROW_H;

        // 第 2 行：惩罚强度 + 惩罚时长（两个短滑块同行）+ 电一下
        punishStrength = Math.max(0, Math.min(absoluteMax, punishStrength));
        punishMillis = Math.max(PUNISH_MS_MIN, Math.min(PUNISH_MS_MAX, punishMillis));
        addRenderableWidget(new ThemedIntSlider(left, y, 132, 20, "惩罚强度", 0, Math.max(0, absoluteMax),
                punishStrength, 1, v -> punishStrength = v));
        addRenderableWidget(new ThemedIntSlider(left + 136, y, 132, 20, "惩罚时长", PUNISH_MS_MIN,
                PUNISH_MS_MAX, punishMillis, PUNISH_MS_STEP, "ms", v -> punishMillis = v));
        addRenderableWidget(new ThemedButton(left + 272, y, 76, 20, Component.literal("电一下"),
                () -> ClientNetworking.sendAction(ControlActionPayload.OP_PUNISH, punishStrength, punishMillis, 0)));
        y += ROW_H;

        // 第 3 行：被控者的「受伤输出强度」总开关（具体系数由被控者自己在他的模组配置里调）
        ThemedButton hpToggle = new ThemedButton(left, y, 240, 20,
                Component.literal("受伤输出强度: " + (hpEnabled != 0 ? "§a开启" : "§c关闭")),
                () -> ClientNetworking.sendAction(ControlActionPayload.OP_HP_TOGGLE, hpEnabled != 0 ? 1 : 0, 0, 0));
        hpToggle.setTooltip(Tooltip.create(Component.literal(
                "关闭后对方受伤和掉血不产生强度，仅取决于你发送的强度")));
        addRenderableWidget(hpToggle);
        y += ROW_H;

        // 效果区：8 行平铺，可滚动。效果名写进滑块 label，省掉一列纯装饰按钮
        effectTop = y;
        effectBottom = this.height - 44;
        int contentHeight = EFFECT_NAMES.length * ROW_H;
        int viewHeight = Math.max(ROW_H, effectBottom - effectTop);
        maxScroll = Math.max(0, contentHeight - viewHeight);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        for (int i = 0; i < EFFECT_NAMES.length; i++) {
            final int index = i;
            int ey = effectTop + i * ROW_H - scrollOffset;
            String label = EFFECT_NAMES[i] + " " + roman(EFFECT_AMPLIFIER[i]);
            AbstractWidget seconds = addRenderableWidget(new ThemedIntSlider(left, ey, 280, 20, label,
                    3, 60, effectSeconds[index], 1, "秒", v -> effectSeconds[index] = v));
            AbstractWidget apply = addRenderableWidget(new ThemedButton(left + 284, ey, 60, 20,
                    Component.literal("施加"),
                    () -> ClientNetworking.sendAction(ControlActionPayload.OP_EFFECT,
                            index, EFFECT_AMPLIFIER[index], effectSeconds[index])));
            boolean visible = ey >= effectTop && ey + 20 <= effectBottom;
            for (AbstractWidget w : new AbstractWidget[]{seconds, apply}) {
                w.visible = visible;
                w.active = visible;
            }
        }

        // 底部
        int by = this.height - 34;
        addRenderableWidget(new ThemedButton(left, by, 80, 20, Component.literal("解绑"), () -> {
            ClientNetworking.sendAction(ControlActionPayload.OP_UNBIND, 0, 0, 0);
            onClose();
        }));
        addRenderableWidget(new ThemedButton(left + PANEL_WIDTH - 80, by, 80, 20,
                Component.literal("关闭"), this::onClose));
    }

    /** 右侧的原版风格滚动条：内容放不下时才画。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, 14, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.literal("§d绑定后可以控制你的小宠物哦～"), centerX, 30, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal(statusLine()), statusX, statusY, 0xFFFFFFFF);

        if (maxScroll <= 0) {
            return;
        }
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int x = left + PANEL_WIDTH + 4;
        int top = effectTop;
        int bottom = effectBottom;
        int viewHeight = bottom - top;
        int trackHeight = viewHeight;
        graphics.fill(x, top, x + 6, top + trackHeight, 0x60000000);

        int contentHeight = viewHeight + maxScroll;
        int thumbHeight = Math.max(20, (int) ((long) viewHeight * viewHeight / Math.max(1, contentHeight)));
        int thumbY = top + (int) ((long) (viewHeight - thumbHeight) * scrollOffset / Math.max(1, maxScroll));
        graphics.fill(x + 1, thumbY, x + 5, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    private String statusLine() {
        PanelStatePayload panel = ClientNetworking.panel();
        if (lastValid == 0) {
            return "§7未绑定 —— 手持郊狼右键绑定戴项圈的目标";
        }
        String name = panel.subjectName();
        if (name == null || name.isEmpty()) {
            return "§a已绑定";
        }
        // 主人不关心被控者的输出数值，但要知道他那边设备有没有连上，否则按了没反应会很懵。
        String device = panel.subjectConnected() != 0 ? "§a设备已连接" : "§c设备未连接";
        return "§a已绑定 §f" + name + " §7· " + device;
    }

    private static String roman(int amplifier) {
        return switch (amplifier) {
            case 0 -> "I";
            case 1 -> "II";
            case 2 -> "III";
            default -> String.valueOf(amplifier + 1);
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
