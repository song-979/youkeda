package com.youkeda.project.wechatproject.controller;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * Provides the iLink QR-code login page.
 */
@RestController
@ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QrCodeController {

    private static final Logger log = LoggerFactory.getLogger(QrCodeController.class);
    private static final QRCodeWriter QR_CODE_WRITER = new QRCodeWriter();
    private static final int QR_CODE_SIZE = 320;
    private static final Map<EncodeHintType, Object> QR_CODE_HINTS = createQrCodeHints();

    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;

    public QrCodeController(ILinkClient ilinkClient, MessageBridge messageBridge) {
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
    }

    @GetMapping(value = "/ilink/qrcode", produces = "text/html;charset=UTF-8")
    public String qrCode() {
        if (ilinkClient.isLoggedIn()) {
            return page("<div class='success-icon'>&#10003;</div>"
                    + "<h2>登录成功</h2>"
                    + "<p>iLink 已成功连接</p>"
                    + "<span class='bot-id'>botId: " + escape(ilinkClient.getLoginContext().getBotId()) + "</span>",
                    "");
        }

        String qrCodeContent = messageBridge.getQrcode();
        if (qrCodeContent == null || qrCodeContent.isEmpty()) {
            return refreshPage(3,
                    "<h2>正在获取二维码</h2><div class='spinner'></div><p>页面每3秒自动刷新</p>",
                    "");
        }

        try {
            String qrCodeSrc = renderToDataUri(qrCodeContent);
            return refreshPage(
                    30,
                    "<div class='logo'>&#128172;</div>"
                            + "<h2>微信扫码登录</h2>"
                            + "<p>请使用微信扫描下方二维码</p>"
                            + "<div class='qr-wrapper'>"
                            + "<img src=\"" + escapeAttribute(qrCodeSrc) + "\" alt='QR Code'/>"
                            + "</div>"
                            + "<p class='refreshing'>页面每30秒自动刷新</p>",
                    "");
        } catch (Exception e) {
            log.error("failed to render QR code from SDK content", e);
            return refreshPage(
                    30,
                    "<div class='logo'>&#9888;</div>"
                            + "<h2>二维码渲染失败</h2>"
                            + "<p>SDK 返回的原始二维码内容，可继续排查：</p>"
                            + "<div class='error-box'><textarea readonly>"
                            + escape(qrCodeContent)
                            + "</textarea></div>"
                            + "<p class='refreshing'>页面每30秒自动刷新</p>",
                    "");
        }
    }

    private static String renderToDataUri(String qrCodeContent) throws WriterException, IOException {
        BitMatrix matrix = QR_CODE_WRITER.encode(
                qrCodeContent.trim(),
                BarcodeFormat.QR_CODE,
                QR_CODE_SIZE,
                QR_CODE_SIZE,
                QR_CODE_HINTS);

        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, matrix.getWidth(), matrix.getHeight());
            graphics.setColor(Color.BLACK);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    if (matrix.get(x, y)) {
                        image.setRGB(x, y, Color.BLACK.getRGB());
                    }
                }
            }
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", outputStream);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private static Map<EncodeHintType, Object> createQrCodeHints() {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, 1);
        return hints;
    }

    private static final String CSS = """
            <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                    display: flex; align-items: center; justify-content: center;
                    min-height: 100vh;
                    background: linear-gradient(135deg, #f0f9f4 0%, #e8f5e9 30%, #dcedc8 60%, #f1f8e9 100%);
                    background-attachment: fixed;
                }
                body::before {
                    content: ""; position: fixed; top: -50%; left: -50%; width: 200%; height: 200%;
                    background: radial-gradient(circle at 30% 20%, rgba(7,193,96,0.06) 0%, transparent 50%),
                                radial-gradient(circle at 70% 80%, rgba(7,193,96,0.05) 0%, transparent 50%),
                                radial-gradient(circle at 50% 50%, rgba(7,193,96,0.03) 0%, transparent 70%);
                    pointer-events: none; z-index: 0;
                }
                .card {
                    position: relative; z-index: 1;
                    background: #ffffff;
                    border-radius: 20px;
                    box-shadow: 0 8px 40px rgba(0,0,0,0.08), 0 2px 8px rgba(0,0,0,0.04);
                    padding: 48px 40px;
                    max-width: 440px; width: 90%;
                    text-align: center;
                    animation: fadeInUp 0.6s ease-out;
                }
                @keyframes fadeInUp {
                    from { opacity: 0; transform: translateY(24px); }
                    to   { opacity: 1; transform: translateY(0); }
                }
                .logo {
                    width: 56px; height: 56px;
                    background: linear-gradient(135deg, #07C160, #06AD56);
                    border-radius: 14px;
                    margin: 0 auto 28px;
                    display: flex; align-items: center; justify-content: center;
                    font-size: 28px; color: #fff;
                }
                h2 {
                    font-size: 22px; color: #1a1a1a; font-weight: 600;
                    letter-spacing: 0.5px; margin-bottom: 12px;
                }
                p { font-size: 14px; color: #999; margin-top: 8px; line-height: 1.6; }
                .qr-wrapper {
                    display: inline-block; padding: 12px;
                    background: #fff; border-radius: 16px;
                    border: 2px solid #e8e8e8;
                    box-shadow: 0 4px 16px rgba(0,0,0,0.04);
                    transition: border-color 0.3s;
                    margin: 8px 0 4px;
                }
                .qr-wrapper:hover { border-color: #07C160; }
                .qr-wrapper img { display: block; border-radius: 8px; max-width: 280px; width: 100%; height: auto; }
                .refreshing { margin-top: 20px; color: #aaa; font-size: 13px; }
                .spinner {
                    width: 40px; height: 40px; margin: 20px auto;
                    border: 3px solid #e8e8e8; border-top-color: #07C160;
                    border-radius: 50%; animation: spin 0.8s linear infinite;
                }
                @keyframes spin { to { transform: rotate(360deg); } }
                .success-icon {
                    width: 64px; height: 64px; margin: 0 auto 20px;
                    background: #e8f5e9; border-radius: 50%;
                    display: flex; align-items: center; justify-content: center;
                    font-size: 32px;
                }
                .bot-id {
                    display: inline-block; background: #f5f5f5; border-radius: 8px;
                    padding: 6px 16px; font-size: 13px; color: #666;
                    font-family: "SF Mono", "Menlo", "Consolas", monospace;
                    margin-top: 12px; word-break: break-all;
                }
                .error-box {
                    background: #fff5f5; border: 1px solid #ffccc7; border-radius: 10px;
                    padding: 16px; margin-top: 12px; text-align: left;
                }
                textarea {
                    width: 100%; height: 160px; border: 1px solid #e0e0e0;
                    border-radius: 8px; padding: 10px; font-size: 12px;
                    font-family: "SF Mono", "Menlo", "Consolas", monospace;
                    resize: vertical; background: #fafafa; color: #555;
                }
                @media (max-width: 480px) {
                    .card { padding: 36px 24px; border-radius: 16px; }
                    h2 { font-size: 19px; }
                }
            </style>
            """;

    private static String page(String body, String bodyStyle) {
        return "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1,minimum-scale=1'>"
                + CSS
                + "</head><body><div class='card' style='" + bodyStyle + "'>"
                + body
                + "</div></body></html>";
    }

    private static String refreshPage(int seconds, String body, String bodyStyle) {
        return "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1,minimum-scale=1'>"
                + "<meta http-equiv='refresh' content='" + seconds + "'>"
                + CSS
                + "</head><body><div class='card' style='" + bodyStyle + "'>"
                + body
                + "</div></body></html>";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("'", "&#39;");
    }
}
