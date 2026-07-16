package com.youkeda.project.wechatproject.ilink;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 提供扫码登录页面。
 */
@RestController
public class QrCodeController {

    private static final Logger log = LoggerFactory.getLogger(QrCodeController.class);
    private static final QRCodeWriter QR_CODE_WRITER = new QRCodeWriter();
    private static final int QR_CODE_SIZE = 320;
    private static final Map<EncodeHintType, Object> QR_CODE_HINTS = createQrCodeHints();

    private final IlinkWechatService service;

    public QrCodeController(IlinkWechatService service) {
        this.service = service;
    }

    @GetMapping(value = "/ilink/qrcode", produces = "text/html;charset=UTF-8")
    public String qrCode() {
        if (service.isLoggedIn()) {
            return page("<h2>已登录成功</h2><p>botId: " + escape(service.getLoginContext().getBotId()) + "</p>",
                    "padding-top:80px;");
        }

        String qrCodeContent = service.getQrCodeContent();
        if (qrCodeContent == null || qrCodeContent.isEmpty()) {
            return refreshPage(3, "<h2>正在获取二维码...</h2><p>页面每3秒自动刷新</p>", "padding-top:80px;");
        }

        try {
            String qrCodeSrc = renderToDataUri(qrCodeContent);
            return refreshPage(
                    30,
                    "<h2>请使用微信扫码登录</h2>"
                            + "<img src=\"" + escapeAttribute(qrCodeSrc) + "\""
                            + " style='max-width:320px;border:2px solid #ccc;padding:10px;background:#fff;'/>"
                            + "<p style='color:#999;margin-top:20px;'>页面每30秒自动刷新</p>",
                    "padding:40px 24px;");
        } catch (Exception e) {
            log.error("failed to render QR code from SDK content", e);
            return refreshPage(
                    30,
                    "<h2>二维码渲染失败</h2>"
                            + "<p>下面是 SDK 返回的原始二维码内容，可继续排查：</p>"
                            + "<textarea style='width:90%;max-width:960px;height:220px;'>"
                            + escape(qrCodeContent)
                            + "</textarea>"
                            + "<p style='color:#999;margin-top:20px;'>页面每30秒自动刷新</p>",
                    "padding:40px 24px;");
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

    private static String page(String body, String bodyStyle) {
        return "<html><body style='font-family:sans-serif;text-align:center;" + bodyStyle + "'>"
                + body
                + "</body></html>";
    }

    private static String refreshPage(int seconds, String body, String bodyStyle) {
        return "<html><head><meta http-equiv='refresh' content='" + seconds + "'></head>"
                + "<body style='font-family:sans-serif;text-align:center;" + bodyStyle + "'>"
                + body
                + "</body></html>";
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
