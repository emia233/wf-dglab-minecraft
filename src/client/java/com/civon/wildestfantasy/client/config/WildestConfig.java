package com.civon.wildestfantasy.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.civon.wildestfantasy.client.dglab.DglabV4Client;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置。首次启动时写入 {@code config/wildestfantasy.json}。
 */
public final class WildestConfig {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static WildestConfig instance;

    /** 强度上限的硬边界（DG-LAB 协议允许 0~200）。 */
    public static final int HARD_MAX_INTENSITY = 200;
    /** 「受伤反馈」两个系数的上限（血量反馈 / 伤害反馈都是 0~30）。 */
    public static final int MAX_FEEDBACK = 30;

    // ---- 连接 ----
    /** V4 中转服务地址。 */
    public String serverUrl = DglabV4Client.DEFAULT_SERVER;
    /** 总开关。 */
    public boolean enabled = true;
    /** 启动客户端后自动连一次中转服务，这样不用进世界也能先拿到配对链接。 */
    public boolean autoConnect = false;

    // ---- 强度 ----
    /**
     * <b>绝对上限</b>（{@code /dglab max} 可调）。惩罚电击、血量映射等瞬时/派生输出都受它约束。
     * APP 端还会各自设置通道上限，实际输出取两者较小值，因此这里只是「模组愿意给出的最大值」。
     */
    public int maxIntensity = 40;
    /**
     * <b>舒适上限</b>（{@code /dglab comfort} 可调）。主人设置的「基础强度」是持续输出，
     * 只能在这个范围内，且永远不会超过 {@link #maxIntensity}。
     */
    public int comfortMax = 20;
    /** A、B 两个通道同时输出。 */
    public boolean syncChannels = true;
    /** 基础强度（平时就保持的轻微输出，0 表示安静）。 */
    public int baseIntensity = 0;

    // ---- 受伤反馈 ----
    /**
     * <b>血量反馈</b>：每缺少 1 点生命值增加的强度（0~30）。
     *
     * <p>例如 20 点满血、当前 15 点、该值为 2，则持续输出 10。
     * JSON 里沿用旧键名 {@code missingHpToIntensity}，这样已经调过的旧配置不会丢。
     */
    @SerializedName("missingHpToIntensity")
    public int hpFeedback = 2;
    /**
     * <b>伤害反馈</b>：每受到 1 点<b>实际伤害</b>（盔甲与状态效果算完之后）增加的强度（0~30）。
     *
     * <p>这一项和 {@link #hpFeedback} 是<b>叠加</b>的：掉血算一份，挨打又算一份。
     * 伤害产生的强度会在 2 秒内线性衰减到 0（见 {@code DamagePulse}），
     * 所以有生命回复、伤害没在血量上体现出来时，这一项仍然能给出反馈。
     */
    public int damageFeedback = 2;
    /**
     * <b>弹射物伤害反馈</b>：被箭、三叉戟这类<b>有伤害</b>的弹射物打中时，瞬间额外输出这么多强度
     * （0 ~ 绝对上限，<b>绝对值不是倍数</b>）。
     *
     * <p>形状是方波：瞬间升上去、保持 300ms、瞬间归零（见 {@code SquarePulse}）。
     * 雪球、风弹这种本身不造成伤害的弹射物不会触发（伤害为 0 就没有伤害事件）。
     */
    public int projectileFeedback = 10;
    /**
     * <b>摔落伤害反馈</b>：摔下来时瞬间额外输出这么多强度（0 ~ 绝对上限），
     * 同样是保持 300ms 的方波。
     */
    public int fallFeedback = 10;

    // ---- 持续伤害 ----
    /**
     * <b>持续伤害反馈</b>：只要身上带着「会持续掉血」的状态（着火、岩浆、岩浆块、仙人掌、中毒、
     * 凋零、溺水、细雪冻伤、饥饿），就稳定输出这么多强度（0 ~ 绝对上限）。
     *
     * <p>多个状态同时存在时<b>不叠加</b>，只按这一份算。强度的上升与下降各用 2 秒线性过渡，
     * 避免开关状态时强度突然跳变（见 {@code SustainedDamage}）。
     *
     * <p>抗火状态下着火/岩浆/岩浆块不产生伤害，因此也不算持续伤害。
     */
    public int sustainedFeedback = 10;

    // ---- 死亡 ----
    public boolean deathEnabled = true;
    /** 死亡瞬间的脉冲强度（0 ~ 绝对上限，模组配置里可调）。 */
    public int deathIntensity = 40;
    /** 死亡后以 {@link #deathIntensity} 持续输出的时长（毫秒，1~10 秒）。 */
    public int deathHoldMs = 2000;

    // ---- 强度 HUD ----
    /** HUD 左上角在屏幕上的横向位置（0~100%）。 */
    public int hudX = 2;
    /** HUD 左上角在屏幕上的纵向位置（0~100%）。 */
    public int hudY = 2;
    /** HUD 文字缩放（50~200%，100% 即原版字号）。 */
    public int hudScalePercent = 100;
    /** 是否显示强度 HUD。 */
    public boolean hudEnabled = true;
    /** 显示哪些通道：0 = AB 双通道，1 = 仅 A，2 = 仅 B。 */
    public int hudChannels = 0;
    /** 主人视角下是否显示「对方」的强度。 */
    public boolean peerHudEnabled = true;
    /** 对方强度显示哪些通道：0 = AB 双通道，1 = 仅 A，2 = 仅 B。 */
    public int peerHudChannels = 0;

    public static WildestConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("wildestfantasy.json");
    }

    public static WildestConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                WildestConfig cfg = GSON.fromJson(json, WildestConfig.class);
                if (cfg != null) {
                    return cfg;
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("读取配置失败，使用默认值: {}", e.getMessage());
            }
        }
        WildestConfig cfg = new WildestConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("保存配置失败: {}", e.getMessage());
        }
    }

    /** 把任意强度夹在 [0, maxIntensity] 内（绝对上限）。 */
    public int clamp(int value) {
        return Math.max(0, Math.min(maxIntensity, value));
    }

    /** 舒适上限的实际取值，永远不超过绝对上限。 */
    public int effectiveComfortMax() {
        return Math.max(0, Math.min(comfortMax, maxIntensity));
    }

    /** 把「基础强度」夹在 [0, effectiveComfortMax()] 内。 */
    public int clampBase(int value) {
        return Math.max(0, Math.min(effectiveComfortMax(), value));
    }
}
