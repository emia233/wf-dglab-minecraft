package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.client.WildestFantasyClient;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.net.ClientNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模组配置面板，按三个大栏目分组：
 *
 * <ol>
 *   <li><b>强度</b> —— 舒适上限、绝对上限、死亡脉冲、受伤/伤害反馈，以及扫码连接</li>
 *   <li><b>强度显示</b> —— HUD 位置与字号、自己强度开关与通道、对方强度开关与通道</li>
 *   <li><b>快捷键</b> —— 三个键位</li>
 * </ol>
 *
 * <p>UI 刻意只用原版控件，和原版设置界面观感一致。内容超过一屏时整页滚动。
 */
public final class ConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int ROW = 26;
    private static final int KEY_ROW = 24;
    private static final int CONTENT_TOP = 36;
    /** 栏目标题占的高度。 */
    private static final int SECTION = 24;
    /** 滑块下方说明小字占的高度。 */
    private static final int HINT = 12;

    private static final int FEEDBACK_MIN = 0;
    private static final int FEEDBACK_MAX = WildestConfig.MAX_FEEDBACK;
    private static final int HUD_SCALE_MIN = 50;
    private static final int HUD_SCALE_MAX = 200;

    /** 一行需要用文字画出来的东西（栏目标题或滑块说明）。 */
    private record TextLine(int x, int y, Component text, boolean header) {
    }

    private final List<TextLine> texts = new ArrayList<>();

    /** 正在等待玩家按键的那个绑定；null 表示不在监听状态。 */
    private KeyMapping awaiting;

    private IntSlider comfortSlider;
    private IntSlider absoluteSlider;
    private IntSlider deathIntensitySlider;
    private IntSlider hpSlider;
    private IntSlider damageSlider;
    private IntSlider sustainedSlider;
    private IntSlider projectileSlider;
    private IntSlider fallSlider;

    private int scrollOffset;
    private int maxScroll;
    private int contentBottom;

    public ConfigScreen() {
        super(Component.literal("模组配置"));
    }

    public static void open() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new ConfigScreen()));
    }

    @Override
    protected void init() {
        awaiting = null;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        texts.clear();

        WildestConfig cfg = WildestConfig.get();
        int left = this.width / 2 - PANEL_WIDTH / 2;
        contentBottom = this.height - 40;

        int y = CONTENT_TOP - scrollOffset;

        // ================= 强度 =================
        y = section(left, y, "强度");

        comfortSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "舒适上限", 0, WildestConfig.HARD_MAX_INTENSITY, cfg.effectiveComfortMax(), 1,
                v -> onComfort(cfg, v)));
        y += ROW;

        absoluteSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "绝对上限", 0, WildestConfig.HARD_MAX_INTENSITY, cfg.maxIntensity, 1,
                v -> onAbsolute(cfg, v)));
        y += ROW;

        int half = (PANEL_WIDTH - 4) / 2;
        deathIntensitySlider = addRenderableWidget(new IntSlider(left, y, half, 20,
                "死亡强度", 0, Math.max(1, cfg.maxIntensity),
                Math.min(cfg.deathIntensity, cfg.maxIntensity), 1,
                v -> onDeathIntensity(cfg, v)));
        addRenderableWidget(new IntSlider(left + half + 4, y, half, 20,
                "死亡时长", 1, 10, clampDeathSeconds(cfg), 1, "秒",
                v -> onDeathSeconds(cfg, v)));
        y += ROW;

        hpSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "受伤反馈", FEEDBACK_MIN, FEEDBACK_MAX, clampFeedback(cfg.hpFeedback), 1,
                v -> onHp(cfg, v)));
        texts.add(new TextLine(left + 2, y + 22,
                Component.literal("§7每失去一点生命值（半颗心），强度 +" + hpSlider.intValue()), false));
        y += ROW + HINT;

        damageSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "伤害反馈", FEEDBACK_MIN, FEEDBACK_MAX, clampFeedback(cfg.damageFeedback), 1,
                v -> onDamage(cfg, v)));
        texts.add(new TextLine(left + 2, y + 22,
                Component.literal("§7每受到一点伤害，强度 +" + damageSlider.intValue()), false));
        y += ROW + HINT;

        sustainedSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "持续伤害反馈", 0, Math.max(1, cfg.maxIntensity),
                Math.min(cfg.sustainedFeedback, cfg.maxIntensity), 1,
                v -> onSustained(cfg, v)));
        texts.add(new TextLine(left + 2, y + 22,
                Component.literal("§7持续掉血的伤害反馈（着火/溺水/中毒等），多个不叠加"), false));
        y += ROW + HINT;

        projectileSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "弹射物伤害反馈", 0, Math.max(1, cfg.maxIntensity),
                Math.min(cfg.projectileFeedback, cfg.maxIntensity), 1,
                v -> onProjectile(cfg, v)));
        texts.add(new TextLine(left + 2, y + 22,
                Component.literal("§7被箭等弹射物打中时增加的强度，持续300ms"), false));
        y += ROW + HINT;

        fallSlider = addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20,
                "摔落伤害反馈", 0, Math.max(1, cfg.maxIntensity),
                Math.min(cfg.fallFeedback, cfg.maxIntensity), 1,
                v -> onFall(cfg, v)));
        texts.add(new TextLine(left + 2, y + 22,
                Component.literal("§7摔落增加的强度，持续300ms"), false));
        y += ROW + HINT;

        addRenderableWidget(Button.builder(Component.literal("扫码连接"), b -> PairingScreen.open())
                .bounds(left, y, PANEL_WIDTH, 20).build());
        y += ROW + 8;

        // ================= 强度显示 =================
        y = section(left, y, "强度显示");

        addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20, "HUD 横向位置",
                0, 100, clampPercent(cfg.hudX), 1, "%", v -> onHudX(cfg, v)));
        y += ROW;
        addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20, "HUD 纵向位置",
                0, 100, clampPercent(cfg.hudY), 1, "%", v -> onHudY(cfg, v)));
        y += ROW;
        addRenderableWidget(new IntSlider(left, y, PANEL_WIDTH, 20, "HUD 字号",
                HUD_SCALE_MIN, HUD_SCALE_MAX, clampHudScale(cfg.hudScalePercent), 10, "%",
                v -> onHudScale(cfg, v)));
        y += ROW;

        y = toggleRow(left, y, cfg, false);
        y = toggleRow(left, y, cfg, true);
        y += 6;

        // ================= 快捷键 =================
        y = section(left, y, "快捷键");
        y = addKeyRow(left, y, "郊狼控制面板", WildestFantasyClient.keyPanel());
        y = addKeyRow(left, y, "模组配置", WildestFantasyClient.keyConfig());
        y = addKeyRow(left, y, "惩罚电击", WildestFantasyClient.keyPunish());

        // 用真实布局高度反推可滚动范围；若当前偏移越界则修正后重排一次
        int realHeight = y + scrollOffset - CONTENT_TOP;
        int newMax = Math.max(0, realHeight - (contentBottom - CONTENT_TOP));
        maxScroll = newMax;
        int clamped = Math.max(0, Math.min(newMax, scrollOffset));
        if (clamped != scrollOffset) {
            scrollOffset = clamped;
            rebuild();
            return;
        }

        updateVisibility();
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(left + PANEL_WIDTH / 2 - 50, contentBottom + 8, 100, 20).build());
    }

    /** 画一个栏目标题，返回下一行的 y。 */
    private int section(int left, int y, String title) {
        texts.add(new TextLine(left, y + 4, Component.literal(title), true));
        return y + SECTION;
    }

    /**
     * 一行两个按钮：开/关 + 通道选择。
     *
     * @param peer true = 对方强度，false = 自己强度
     */
    private int toggleRow(int left, int y, WildestConfig cfg, boolean peer) {
        String head = peer ? "被控强度：" : "自己强度：";
        int nameWidth = Minecraft.getInstance().font.width(head);
        texts.add(new TextLine(left + 2, y + 6, Component.literal("§f" + head), false));

        int btnX = left + nameWidth + 6;
        int btnW = (PANEL_WIDTH - nameWidth - 10) / 2;

        addRenderableWidget(Button.builder(
                Component.literal(peer
                        ? (cfg.peerHudEnabled ? "显示中" : "已隐藏")
                        : (cfg.hudEnabled ? "显示中" : "已隐藏")),
                b -> {
                    if (peer) {
                        cfg.peerHudEnabled = !cfg.peerHudEnabled;
                        b.setMessage(Component.literal(cfg.peerHudEnabled ? "显示中" : "已隐藏"));
                    } else {
                        cfg.hudEnabled = !cfg.hudEnabled;
                        b.setMessage(Component.literal(cfg.hudEnabled ? "显示中" : "已隐藏"));
                    }
                    cfg.save();
                    rebuild();
                }).bounds(btnX, y, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal(channelLabel(peer ? cfg.peerHudChannels : cfg.hudChannels)),
                b -> {
                    if (peer) {
                        cfg.peerHudChannels = (cfg.peerHudChannels + 1) % 3;
                    } else {
                        cfg.hudChannels = (cfg.hudChannels + 1) % 3;
                    }
                    cfg.save();
                    rebuild();
                }).bounds(btnX + btnW + 4, y, btnW, 20).build());

        return y + ROW;
    }

    /** 滚动裁剪：完全落在可视区外的控件直接隐藏。 */
    private void updateVisibility() {
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                boolean inView = widget.getY() + widget.getHeight() > CONTENT_TOP
                        && widget.getY() < contentBottom;
                widget.visible = inView;
                widget.active = inView;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int step = scrollY > 0 ? -ROW : scrollY < 0 ? ROW : 0;
        if (step != 0) {
            int next = Math.max(0, Math.min(maxScroll, scrollOffset + step));
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, Component.literal("WF dglab 模组配置"), this.width / 2, 10, 0xFFFFFFFF);
        graphics.fill(this.width / 2 - PANEL_WIDTH / 2, 26,
                this.width / 2 + PANEL_WIDTH / 2, 27, 0x40FFFFFF);

        int left = this.width / 2 - PANEL_WIDTH / 2;
        for (TextLine line : texts) {
            if (line.y() < CONTENT_TOP - 10 || line.y() > contentBottom) {
                continue;
            }
            if (line.header()) {
                graphics.text(this.font, line.text(), line.x(), line.y(), 0xFFFFFFFF);
                graphics.fill(line.x(), line.y() + 11, line.x() + PANEL_WIDTH, line.y() + 12, 0x40FFFFFF);
            } else {
                graphics.text(this.font, line.text(), line.x(), line.y(), 0xFFA0A0A0);
            }
        }

        if (maxScroll <= 0) {
            return;
        }
        int x = left + PANEL_WIDTH + 4;
        int viewHeight = contentBottom - CONTENT_TOP;
        int contentHeight = viewHeight + maxScroll;
        graphics.fill(x, CONTENT_TOP, x + 6, contentBottom, 0x60000000);
        int thumbHeight = Math.max(20, (int) ((long) viewHeight * viewHeight / Math.max(1, contentHeight)));
        int thumbY = CONTENT_TOP
                + (int) ((long) (viewHeight - thumbHeight) * scrollOffset / Math.max(1, maxScroll));
        graphics.fill(x + 1, thumbY, x + 5, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    private static String channelLabel(int channels) {
        return switch (channels) {
            case 1 -> "仅A通道";
            case 2 -> "仅B通道";
            default -> "AB双通道";
        };
    }

    private int addKeyRow(int left, int y, String label, KeyMapping mapping) {
        texts.add(new TextLine(left + 2, y + 6, Component.literal("§f" + label), false));
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
        String text = awaiting == mapping
                ? "> 按任意键…"
                : bound.getDisplayName().getString();
        addRenderableWidget(Button.builder(Component.literal(text), b -> {
            awaiting = mapping;
            rebuild();
        }).bounds(left + 154, y, 170, 20).build());
        addRenderableWidget(Button.builder(Component.literal("清除"), b -> {
            mapping.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            rebuild();
        }).bounds(left + 328, y, 92, 20).build());
        return y + KEY_ROW;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (awaiting != null) {
            InputConstants.Key key = InputConstants.getKey(event);
            if (key.getValue() == InputConstants.KEY_ESCAPE) {
                awaiting = null;
                rebuild();
                return true;
            }
            awaiting.setKey(key);
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            awaiting = null;
            rebuild();
            return true;
        }
        return super.keyPressed(event);
    }

    private void onComfort(WildestConfig cfg, int v) {
        cfg.comfortMax = v;
        // 绝对上限不能比舒适上限还低：拖动舒适上限时把绝对上限一起抬上去
        if (v > cfg.maxIntensity) {
            cfg.maxIntensity = v;
            if (absoluteSlider != null) {
                absoluteSlider.setIntValue(v);
            }
        }
        cfg.save();
        ClientNetworking.reportLimits(cfg);
    }

    private void onAbsolute(WildestConfig cfg, int v) {
        cfg.maxIntensity = v;
        if (v < cfg.effectiveComfortMax()) {
            cfg.comfortMax = v;
            if (comfortSlider != null) {
                comfortSlider.setIntValue(v);
            }
        }
        // 受绝对上限约束的滑块要当场收窄，否则还能拖到旧上限（显示与实际不符）
        int upper = Math.max(1, v);
        if (deathIntensitySlider != null) {
            deathIntensitySlider.setMax(upper);
            cfg.deathIntensity = Math.min(cfg.deathIntensity, upper);
        }
        if (sustainedSlider != null) {
            sustainedSlider.setMax(upper);
            cfg.sustainedFeedback = Math.min(cfg.sustainedFeedback, upper);
        }
        if (projectileSlider != null) {
            projectileSlider.setMax(upper);
            cfg.projectileFeedback = Math.min(cfg.projectileFeedback, upper);
        }
        if (fallSlider != null) {
            fallSlider.setMax(upper);
            cfg.fallFeedback = Math.min(cfg.fallFeedback, upper);
        }
        cfg.save();
        ClientNetworking.reportLimits(cfg);
    }

    private void onDeathIntensity(WildestConfig cfg, int v) {
        cfg.deathIntensity = v;
        cfg.save();
    }

    private void onDeathSeconds(WildestConfig cfg, int v) {
        cfg.deathHoldMs = v * 1000;
        cfg.save();
    }

    /** 死亡时长存的是毫秒，滑块用秒（1~10），四舍五入到最近一秒。 */
    private static int clampDeathSeconds(WildestConfig cfg) {
        int seconds = Math.round(cfg.deathHoldMs / 1000.0f);
        return Math.max(1, Math.min(10, seconds));
    }

    private void onHp(WildestConfig cfg, int v) {
        cfg.hpFeedback = v;
        cfg.save();
    }

    private void onDamage(WildestConfig cfg, int v) {
        cfg.damageFeedback = v;
        cfg.save();
    }

    private void onSustained(WildestConfig cfg, int v) {
        cfg.sustainedFeedback = Math.max(0, Math.min(cfg.maxIntensity, v));
        cfg.save();
    }

    private void onProjectile(WildestConfig cfg, int v) {
        cfg.projectileFeedback = Math.max(0, Math.min(cfg.maxIntensity, v));
        cfg.save();
    }

    private void onFall(WildestConfig cfg, int v) {
        cfg.fallFeedback = Math.max(0, Math.min(cfg.maxIntensity, v));
        cfg.save();
    }

    private void onHudX(WildestConfig cfg, int v) {
        cfg.hudX = v;
        cfg.save();
    }

    private void onHudY(WildestConfig cfg, int v) {
        cfg.hudY = v;
        cfg.save();
    }

    private void onHudScale(WildestConfig cfg, int v) {
        cfg.hudScalePercent = v;
        cfg.save();
    }

    private static int clampFeedback(int v) {
        return Math.max(FEEDBACK_MIN, Math.min(FEEDBACK_MAX, v));
    }

    private static int clampPercent(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int clampHudScale(int v) {
        return Math.max(HUD_SCALE_MIN, Math.min(HUD_SCALE_MAX, v));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
