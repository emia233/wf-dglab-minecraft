package com.civon.wildestfantasy.client.screen;

import com.civon.wildestfantasy.client.WildestFantasyClient;
import com.civon.wildestfantasy.client.config.WildestConfig;
import com.civon.wildestfantasy.client.dglab.DglabV4Client;
import com.civon.wildestfantasy.client.dglab.QrCodeService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    private Button localButton;
    private EditBox portField;

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
        // 默认走官方中转；已经在本地直连（或已连上中转）时不重复连
        if (dglab.state() == DglabV4Client.State.DISCONNECTED) {
            dglab.connect(WildestConfig.get().serverUrl);
        }

        int bottom = this.height - 62;
        addRenderableWidget(Button.builder(Component.literal("重新打开图片"), b -> openPng())
                .bounds(this.width / 2 - 154, bottom, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制地址"), b -> copyAddress())
                .bounds(this.width / 2 - 30, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(this.width / 2 + 74, bottom, 80, 20).build());

        // 本地直连入口：官方中转不通（或手机就在同一 WiFi）时用这条。
        // 左边小输入框给端口，右边是按钮，一起居中。
        WildestConfig cfg = WildestConfig.get();
        int fieldW = 70;
        int buttonW = 150;
        int rowW = fieldW + 4 + buttonW;
        int rowX = this.width / 2 - rowW / 2;

        portField = new EditBox(this.font, rowX, bottom + 26, fieldW, 20, Component.literal("端口"));
        portField.setMaxLength(5);
        portField.setValue(String.valueOf(cfg.localPort));
        addRenderableWidget(portField);

        String label = dglab.isLocalMode() ? "重新开始本地连接" : "使用本地连接";
        localButton = addRenderableWidget(Button.builder(Component.literal(label), b -> startLocal())
                .bounds(rowX + fieldW + 4, bottom + 26, buttonW, 20).build());
    }

    /** 输入框里的端口，非法就退回默认值。 */
    private int parsePort() {
        if (portField == null) {
            return WildestConfig.get().localPort;
        }
        String text = portField.getValue().trim();
        try {
            int value = Integer.parseInt(text);
            return value > 0 && value < 65536 ? value : WildestConfig.get().localPort;
        } catch (NumberFormatException ignored) {
            return WildestConfig.get().localPort;
        }
    }

    /** 关掉当前连接，改用本地直连，配对码每次重新随机。 */
    private void startLocal() {
        DglabV4Client dglab = WildestFantasyClient.dglab();
        WildestConfig cfg = WildestConfig.get();
        int port = parsePort();
        cfg.localPort = port;
        cfg.save();
        if (portField != null) {
            portField.setValue(String.valueOf(port));
        }

        dglab.disconnect();
        boolean ok = dglab.connectLocal(port, randomPairingCode());
        revealed = false;
        lastError = ok ? null : dglab.lastError();
        if (ok && localButton != null) {
            localButton.setMessage(Component.literal("重新开始本地连接"));
        }
    }

    /** 6 位小写十六进制，够用又不用记。 */
    private static String randomPairingCode() {
        StringBuilder sb = new StringBuilder(6);
        var random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
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

        boolean local = dglab.isLocalMode();
        String status;
        if (local) {
            status = switch (dglab.state()) {
                case READY -> "§aApp 已连接";
                case WAITING_APP -> revealed
                        ? "§a二维码已生成，手机需与本机在同一 WiFi"
                        : "§7正在生成本地二维码…";
                default -> "§c本地服务器未启动";
            };
        } else {
            status = switch (dglab.state()) {
                case READY -> "§aApp 已连接";
                case WAITING_APP -> revealed
                        ? "§a二维码已生成，请使用 DG-LAB 4.0 APP 的 socket 扫描刚打开的图片"
                        : "§7正在生成二维码…";
                case CONNECTING -> "§7正在连接中转服务…";
                default -> "§c未连接中转服务";
            };
        }
        graphics.centeredText(this.font, Component.literal(status), centerX, y, 0xFFFFFFFF);
        y += 22;

        if (local) {
            String info = "§7本地端口 " + dglab.localPort();
            graphics.centeredText(this.font, Component.literal(info), centerX, y, 0xFFAAAAAA);
            y += 14;
        }

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

        // 本地连接的排查提示，三条单独一行便于对号入座
        graphics.centeredText(this.font,
                Component.literal("§a如果官方中转连接超时，可使用本地连接，检查："), centerX, y, 0xFF55FF55);
        y += 12;
        graphics.centeredText(this.font,
                Component.literal("§e1. 同一 wifi 且无客户端隔离"), centerX, y, 0xFFFFE060);
        y += 11;
        graphics.centeredText(this.font,
                Component.literal("§e2. 防火墙已放行"), centerX, y, 0xFFFFE060);
        y += 11;
        graphics.centeredText(this.font,
                Component.literal("§e3. 端口未被占用"), centerX, y, 0xFFFFE060);
        y += 14;

        // 不显示 ws 地址（太长且玩家用不上），但「复制地址」按钮留着兜底。
        // 图片路径常常比屏幕还宽（PCL 的实例目录带空格和中文），自动折行。
        var pathLines = this.font.split(
                Component.literal("§7图片位置：" + WildestFantasyClient.configDir().resolve("qr.png")),
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
