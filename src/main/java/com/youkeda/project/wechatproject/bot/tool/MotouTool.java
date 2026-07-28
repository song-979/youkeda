package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent.tools.motou")
@ConditionalOnProperty(prefix = "agent.tools.motou", name = "enabled", havingValue = "true")
public class MotouTool implements ProjectTool {

    @Override
    public String category() { return "media_generation"; }

    private static final Logger log = LoggerFactory.getLogger(MotouTool.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ThreadLocal<String> LAST_GIF_PATH = new ThreadLocal<>();

    private boolean enabled = false;
    private String apiKey;
    private String apiUrl = "https://uapis.cn";
    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 60000;
    private String outputDir = "target/motou";

    @Tool(name = "generate_motou_gif_from_url",
          description = "根据图片URL生成摸摸头GIF动图。传入一个公开可访问的图片URL地址，生成可爱的摸摸头动画表情。当用户想用某个网络图片制作摸摸头表情包时调用。")
    public String generateMotouGifFromUrl(
            @ToolParam(description = "图片的URL地址，如 https://example.com/avatar.jpg") String imageUrl,
            @ToolParam(description = "GIF背景颜色，可选 white/black/transparent，默认 transparent") String bgColor) {
        if (apiKey == null || apiKey.isBlank()) {
            return "摸摸头GIF生成功能未配置，请联系管理员设置 UAPI API Key。";
        }
        String effectiveUrl = imageUrl != null ? imageUrl.trim() : "";
        if (effectiveUrl.isEmpty()) {
            return "图片URL为空，请提供要制作摸摸头GIF的图片地址。";
        }

        log.info("generate_motou_gif_from_url invoked: imageUrl={}, bgColor={}", effectiveUrl, bgColor);

        var formData = new LinkedMultiValueMap<String, Object>();
        formData.add("image_url", effectiveUrl);
        addBgColor(formData, bgColor);

        return executeMotou(formData, "URL:" + effectiveUrl);
    }

    @Tool(name = "generate_motou_gif_from_image",
          description = "根据用户聊天中发送的图片生成摸摸头GIF动图。无需提供图片数据，工具会自动使用用户刚发送的图片。当用户发送了图片并想把它做成摸摸头表情包时调用。")
    public String generateMotouGifFromImage(
            @ToolParam(description = "GIF背景颜色，可选 white/black/transparent，默认 transparent") String bgColor,
            ToolContext toolContext) {
        if (apiKey == null || apiKey.isBlank()) {
            return "摸摸头GIF生成功能未配置，请联系管理员设置 UAPI API Key。";
        }
        if (toolContext == null || toolContext.getContext().isEmpty()) {
            return "摸摸头GIF生成失败：无法获取当前消息的图片数据。";
        }

        @SuppressWarnings("unchecked")
        List<String> imageUrls = (List<String>) toolContext.getContext().get("imageBase64Urls");
        if (imageUrls == null || imageUrls.isEmpty()) {
            return "未找到图片数据，请确保发送了图片后再调用此工具。";
        }
        String base64DataUrl = imageUrls.get(0);

        log.info("generate_motou_gif_from_image invoked via tool context: base64Len={}, bgColor={}",
                base64DataUrl.length(), bgColor);

        try {
            byte[] imageBytes = decodeBase64DataUrl(base64DataUrl);
            String extension = detectExtension(base64DataUrl);
            Path tempFile = Files.createTempFile("motou_input_", "." + extension);
            Files.write(tempFile, imageBytes);

            log.info("decoded base64 image, size={} bytes, tempFile={}", imageBytes.length, tempFile);

            var formData = new LinkedMultiValueMap<String, Object>();
            formData.add("file", new org.springframework.core.io.FileSystemResource(tempFile.toFile()));
            addBgColor(formData, bgColor);

            String result = executeMotou(formData, "base64_image");
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            return result;
        } catch (IllegalArgumentException e) {
            return "图片数据格式错误：" + e.getMessage();
        } catch (IOException e) {
            log.error("failed to write temp image file", e);
            return "处理图片数据时出错：" + e.getMessage();
        }
    }

    // ---- core execution ----

    private String executeMotou(LinkedMultiValueMap<String, Object> formData, String sourceDescription) {
        try {
            RestTemplate restTemplate = buildFormDataRestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<LinkedMultiValueMap<String, Object>> request = new HttpEntity<>(formData, headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    apiUrl + "/api/v1/image/motou",
                    HttpMethod.POST,
                    request,
                    byte[].class);

            byte[] gifBytes = response.getBody();
            if (gifBytes == null || gifBytes.length == 0) {
                return "摸摸头GIF生成失败，API 返回内容为空。";
            }

            Path outputPath = saveGif(gifBytes);

            log.info("motou GIF saved: path={}, size={} bytes", outputPath, gifBytes.length);

            String gifPath = outputPath.toAbsolutePath().normalize().toString();
            LAST_GIF_PATH.set(gifPath);

            return "[MOTOU_GIF:" + gifPath + "]\n"
                    + "摸摸头GIF已生成！大小: " + gifBytes.length + " bytes";
        } catch (Exception e) {
            log.error("motou GIF generation failed for source={}", sourceDescription, e);

            String msg = e.getMessage();
            if (msg != null && msg.contains("400")) {
                return "摸摸头GIF生成失败：请求参数错误，请检查图片URL是否有效。";
            }
            return "摸摸头GIF生成失败：" + (msg != null ? msg : "未知错误") + "。请稍后重试或尝试其他图片。";
        }
    }

    // ---- helpers ----

    private void addBgColor(LinkedMultiValueMap<String, Object> formData, String bgColor) {
        if (bgColor != null && !bgColor.isBlank()) {
            String normalized = bgColor.trim().toLowerCase();
            if ("white".equals(normalized) || "black".equals(normalized) || "transparent".equals(normalized)) {
                formData.add("bg_color", normalized);
            }
        }
    }

    private Path saveGif(byte[] gifBytes) throws IOException {
        Path dir = Path.of(outputDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String filename = "motou_" + LocalDateTime.now().format(TS_FMT) + ".gif";
        Path filePath = dir.resolve(filename);
        Files.write(filePath, gifBytes);
        return filePath;
    }

    private static byte[] decodeBase64DataUrl(String dataUrl) {
        // data:image/png;base64,iVBORw0KGgo...
        if (!dataUrl.startsWith("data:")) {
            throw new IllegalArgumentException("not a valid data URL, expected data:image/...;base64,...");
        }
        int base64Idx = dataUrl.indexOf(";base64,");
        if (base64Idx < 0) {
            throw new IllegalArgumentException("data URL missing ;base64, prefix");
        }
        String base64 = dataUrl.substring(base64Idx + 8); // after ";base64,"
        return Base64.getDecoder().decode(base64);
    }

    private static String detectExtension(String dataUrl) {
        if (dataUrl.contains("image/png")) return "png";
        if (dataUrl.contains("image/jpg") || dataUrl.contains("image/jpeg")) return "jpg";
        if (dataUrl.contains("image/gif")) return "gif";
        if (dataUrl.contains("image/webp")) return "webp";
        return "png";
    }

    private RestTemplate buildFormDataRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setMessageConverters(List.of(
                new FormHttpMessageConverter(),
                new ByteArrayHttpMessageConverter()));
        return restTemplate;
    }

    // ---- getters / setters for @ConfigurationProperties binding ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public static String getAndClearLastGifPath() {
        String path = LAST_GIF_PATH.get();
        LAST_GIF_PATH.remove();
        return path;
    }
}
