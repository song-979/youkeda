package com.youkeda.project.wechatproject.agent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

public record GeneratedImage(byte[] bytes, String fileName, String mediaType) {

    private static final int MAX_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.9f;

    public GeneratedImage {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
    }

    public static GeneratedImage normalize(byte[] rawBytes, String baseName) throws IOException {
        Objects.requireNonNull(rawBytes, "rawBytes must not be null");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rawBytes));
        if (decoded == null) {
            String extension = detectExtension(rawBytes);
            String type = detectMediaType(rawBytes);
            return new GeneratedImage(rawBytes, ensureExtension(baseName, extension), type);
        }

        BufferedImage scaled = resizeIfNeeded(decoded);
        boolean hasAlpha = scaled.getColorModel().hasAlpha();
        if (hasAlpha) {
            return new GeneratedImage(writePng(scaled), ensureExtension(baseName, "png"), "image/png");
        }
        return new GeneratedImage(writeJpeg(scaled), ensureExtension(baseName, "jpg"), "image/jpeg");
    }

    public String dataUrl() {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static BufferedImage resizeIfNeeded(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int max = Math.max(width, height);
        if (max <= MAX_DIMENSION) {
            return src;
        }

        double ratio = (double) MAX_DIMENSION / max;
        int newWidth = Math.max(1, (int) Math.round(width * ratio));
        int newHeight = Math.max(1, (int) Math.round(height * ratio));

        int imageType = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, imageType);
        Graphics2D g = scaled.createGraphics();
        g.drawImage(src, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("failed to encode image as png");
        }
        return out.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("jpeg writer not available");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
        }

        writer.setOutput(new MemoryCacheImageOutputStream(out));
        writer.write(null, new IIOImage(rgb, null, null), param);
        writer.dispose();
        return out.toByteArray();
    }

    private static String detectExtension(byte[] bytes) {
        if (startsWith(bytes, (byte) 0x89, 'P', 'N', 'G')) {
            return "png";
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) {
            return "jpg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return "gif";
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && containsAt(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "webp";
        }
        return "bin";
    }

    private static String detectMediaType(byte[] bytes) {
        return switch (detectExtension(bytes).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private static boolean startsWith(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[i] & 0xFF) != (magic[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAt(byte[] bytes, int offset, int... magic) {
        if (bytes.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (magic[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String ensureExtension(String baseName, String extension) {
        String normalizedBase = (baseName == null || baseName.isBlank()) ? "generated" : baseName;
        int dotIndex = normalizedBase.lastIndexOf('.');
        if (dotIndex > 0) {
            normalizedBase = normalizedBase.substring(0, dotIndex);
        }
        return normalizedBase + "." + extension;
    }
}
