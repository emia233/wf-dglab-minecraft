package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.client.WildestFantasyClient;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.dglab.DglabV4Client;
import com.civon.wildestfantasy.client.dglab.QrCodeService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 扫码连接界面。
 *
 * <p>打开时自动连接中转服务；拿到控制方 ID 后把配对二维码写成 PNG 并交给系统图片查看器打开，
 * 手机对着屏幕上的图片扫即可。游戏内不再绘制二维码（26.3 的动态纹理上传路径不稳定）。
 */
public final class PairingScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/pairing");

    private boolean revealed;
    private String lastError;

    public PairingScreen() {
        super(Component.literal("扫码连接"));
    }

    public static void open() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new PairingScreen()));
    }

    @Override
    protected void init() {
        DglabV4Client dglab = WildestFantasyClient.dglab();
        if (dglab.state() == DglabV4Client.State.DISCONNECTED) {
            dglab.connect(WildestConfig.get().serverUrl);
        }

        int bottom = this.height - 40;
        addRenderableWidget(Button.builder(Component.literal("重新打开图片"), b -> openPng())
                .bounds(this.width / 2 - 154, bottom, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制地址"), b -> copyAddress())
                .bounds(this.width / 2 - 30, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(this.width / 2 + 74, bottom, 80, 20).build());
    }

    @Override
    public void tick() {
        if (revealed) {
            return;
        }
        DglabV4Client dglab = WildestFantasyClient.dglab();
        if (dglab.qrCodeUrl() != null) {
            revealed = openPng();
        }
    }

    /** 写二维码并把系统图片查看器叫起来。 */
    private boolean openPng() {
        DglabV4Client dglab = WildestFantasyClient.dglab();
        String content = dglab.qrCodeUrl();
        if (content == null) {
            lastError = "还没有拿到配对地址";
            return false;
        }
        try {
            Path file = QrCodeService.write(content, WildestFantasyClient.configDir().resolve("qr.png"));
            QrCodeService.reveal(file);
            lastError = null;
            return true;
        } catch (Throwable t) {
            LOG.warn("写出二维码图片失败", t);
            lastError = "写出二维码失败：" + t.getMessage();
            return false;
        }
    }

    private void copyAddress() {
        String ws = WildestFantasyClient.dglab().pairingUrl();
        if (ws == null) {
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(ws);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        DglabV4Client dglab = WildestFantasyClient.dglab();
        int centerX = this.width / 2;
        int y = 50;

        graphics.centeredText(this.font, this.title, centerX, 20, 0xFFFFFFFF);

        String status = switch (dglab.state()) {
            case READY -> "§aAPP 已接入，郊狼就绪";
            case WAITING_APP -> revealed
                    ? "§a二维码已生成，请使用 DG-LAB 4.0 APP 的 socket 扫描刚打开的图片"
                    : "§7正在生成二维码…";
            case CONNECTING -> "§7正在连接中转服务…";
            default -> "§c未连接中转服务";
        };
        graphics.centeredText(this.font, Component.literal(status), centerX, y, 0xFFFFFFFF);
        y += 22;

        // 安全提示：红字，太宽就自动折成多行
        var warnLines = this.font.split(
                Component.literal("§c请在 App 中设置合适的安全上限，并且与你信任的人使用，"
                        + "该模组不对使用外部设备可能造成的伤害负责"),
                Math.max(80, this.width - 60));
        for (var line : warnLines) {
            graphics.centeredText(this.font, line, centerX, y, 0xFFFF5555);
            y += 10;
        }
        y += 8;

        // 不显示 ws 地址（太长且玩家用不上），但「复制地址」按钮留着兜底。
        // 图片路径常常比屏幕还宽（PCL 的实例目录带空格和中文），自动折行。
        var pathLines = this.font.split(
                Component.literal("§8图片位置：" + WildestFantasyClient.configDir().resolve("qr.png")),
                Math.max(80, this.width - 40));
        for (var line : pathLines) {
            graphics.centeredText(this.font, line, centerX, y, 0xFF808080);
            y += 10;
        }
        y += 4;

        if (lastError != null) {
            graphics.centeredText(this.font, Component.literal("§c" + lastError), centerX, y + 20, 0xFFFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
