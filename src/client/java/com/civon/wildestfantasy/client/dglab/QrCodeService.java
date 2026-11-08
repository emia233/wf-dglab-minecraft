package com.civon.wildestfantasy.client.dglab;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mojang.blaze3d.platform.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 把配对链接画成二维码 PNG，并唤起资源管理器定位到该文件。
 *
 * <p>文件名固定（{@code config/wildestfantasy/qr.png}），每次连接直接覆盖，
 * 所以那个文件夹里永远只有一张图，不会越攒越多。
 */
public final class QrCodeService {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/qr");

    /** 输出边长，手机摄像头扫 512px 足够清晰，也不会太大。 */
    private static final int SIZE = 512;

    private QrCodeService() {
    }

    /**
     * 生成二维码 PNG（已存在则覆盖）。
     *
     * @param content 二维码内容，这里放的是 DG-LAB 的配对深链
     * @param out     目标文件
     */
    public static Path write(String content, Path out) throws Exception {
        BufferedImage image = render(content);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        if (!ImageIO.write(image, "PNG", out.toFile())) {
            throw new IOException("没有可用的 PNG 编码器");
        }
        return out;
    }

    /**
     * 直接渲染成游戏纹理，供界面上就地显示。
     *
     * <p>走 PNG 字节再让 {@code NativeImage} 解码，避免自己去拼像素格式。
     */
    public static NativeImage toNativeImage(String content) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(render(content), "PNG", buf);
        return NativeImage.read(buf.toByteArray());
    }

    private static BufferedImage render(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);

        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    /**
     * 打开二维码所在目录。失败只记日志，不影响游戏。
     *
     * <p>Windows 上不能用 {@code explorer /select,<path>}：路径含空格时（PCL 的实例目录就叫
     * {@code 26.3-Fabric 0.20.1}）ProcessBuilder 自动加的引号会包住整个 {@code /select,...} 参数，
     * explorer 解析不了，结果是打开了别的目录。这里改成用 AWT 直接打开所在目录，
     * 再退回 {@code explorer.exe <dir>}（参数只有路径本身，引号位置不会错）。
     */
    public static void reveal(Path file) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            Path dir = file.getParent() != null ? file.getParent().toAbsolutePath() : file.toAbsolutePath();

            if (os.contains("win")) {
                if (openWithDesktop(dir)) {
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder("explorer.exe", dir.toString());
                pb.redirectErrorStream(true);
                pb.start();
                return;
            }

            ProcessBuilder pb = os.contains("mac")
                    ? new ProcessBuilder("open", "-R", file.toAbsolutePath().toString())
                    : new ProcessBuilder("xdg-open", dir.toString());
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception e) {
            LOG.warn("打开二维码所在文件夹失败: {}", e.getMessage());
        }
    }

    /** 用 AWT 打开目录；不支持或失败返回 false。 */
    private static boolean openWithDesktop(Path dir) {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                return false;
            }
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                desktop.open(dir.toFile());
                return true;
            }
        } catch (Exception e) {
            LOG.warn("AWT 打开目录失败，改用 explorer: {}", e.getMessage());
        }
        return false;
    }
}
