package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.tools.webparse")
@ConditionalOnProperty(prefix = "agent.tools.webparse", name = "enabled", havingValue = "true")
public class WebParseTool implements ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(WebParseTool.class);

    @Override
    public String category() { return "web_content"; }
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private boolean enabled = false;
    private String apiKey;
    private String apiUrl = "https://uapis.cn";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private int maxImageCount = 10;

    @Tool(name = "get_webpage_metadata",
          description = "获取网页的元数据信息，包括标题、描述、关键词、Favicon图标地址、Open Graph信息等。当用户分享链接、询问网页内容摘要、或需要生成链接预览卡片时调用。传入完整的网页URL。")
    public String getWebpageMetadata(
            @ToolParam(description = "需要提取元数据的完整网页URL，如 https://www.example.com/article/123") String url) {
        if (apiKey == null || apiKey.isBlank()) {
            return "网页解析功能未配置，请联系管理员设置 UAPI API Key。";
        }
        String effectiveUrl = url != null ? url.trim() : "";
        if (effectiveUrl.isEmpty()) {
            return "URL 为空，请提供要解析的网页地址。";
        }

        log.info("get_webpage_metadata invoked: url={}", effectiveUrl);

        try {
            String fullUrl = UriComponentsBuilder.fromUriString(apiUrl + "/api/v1/webparse/metadata")
                    .queryParam("url", effectiveUrl)
                    .build().encode().toUriString();

            ResponseEntity<String> response = buildRestTemplate().exchange(
                    fullUrl, HttpMethod.GET, authHeaders(), String.class);

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return "未能解析网页 \"" + effectiveUrl + "\"，返回内容为空。";
            }

            Map<String, Object> parsed = objectMapper.readValue(body,
                    new TypeReference<Map<String, Object>>() {});
            return formatMetadata(effectiveUrl, parsed);
        } catch (Exception e) {
            log.error("get_webpage_metadata failed for url={}", effectiveUrl, e);
            return "解析网页 \"" + effectiveUrl + "\" 时出错：" + e.getMessage();
        }
    }

    @Tool(name = "extract_webpage_images",
          description = "提取网页中所有图片的链接地址。当用户想获取某个页面的图片、查看网页中的插图、或需要收集素材时调用。传入完整的网页URL。")
    public String extractWebpageImages(
            @ToolParam(description = "需要提取图片的完整网页URL，如 https://www.example.com/gallery") String url) {
        if (apiKey == null || apiKey.isBlank()) {
            return "网页解析功能未配置，请联系管理员设置 UAPI API Key。";
        }
        String effectiveUrl = url != null ? url.trim() : "";
        if (effectiveUrl.isEmpty()) {
            return "URL 为空，请提供要提取图片的网页地址。";
        }

        log.info("extract_webpage_images invoked: url={}", effectiveUrl);

        try {
            String fullUrl = UriComponentsBuilder.fromUriString(apiUrl + "/api/v1/webparse/extractimages")
                    .queryParam("url", effectiveUrl)
                    .build().encode().toUriString();

            ResponseEntity<String> response = buildRestTemplate().exchange(
                    fullUrl, HttpMethod.GET, authHeaders(), String.class);

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return "未能从网页 \"" + effectiveUrl + "\" 提取到图片，页面可能没有图片或无法访问。";
            }

            Map<String, Object> parsed = objectMapper.readValue(body,
                    new TypeReference<Map<String, Object>>() {});
            return formatImages(effectiveUrl, parsed);
        } catch (Exception e) {
            log.error("extract_webpage_images failed for url={}", effectiveUrl, e);
            return "提取网页 \"" + effectiveUrl + "\" 图片时出错：" + e.getMessage();
        }
    }

    // ---- formatting ----

    private String formatMetadata(String url, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("网页 \"").append(url).append("\" 的元数据：\n\n");

        appendField(sb, "标题", str(data, "title"));
        appendField(sb, "描述", str(data, "description"));
        appendField(sb, "语言", str(data, "language"));
        appendField(sb, "作者", str(data, "author"));
        appendField(sb, "发布时间", str(data, "published_time"));
        appendField(sb, "Favicon", str(data, "favicon_url"));

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) data.get("keywords");
        if (keywords != null && !keywords.isEmpty()) {
            sb.append("关键词：").append(String.join("、", keywords)).append("\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> og = (Map<String, Object>) data.get("open_graph");
        if (og != null && !og.isEmpty()) {
            sb.append("\nOpen Graph 信息：\n");
            appendField(sb, "  OG 标题", str(og, "title"));
            appendField(sb, "  OG 描述", str(og, "description"));
            appendField(sb, "  OG 图片", str(og, "image"));
            appendField(sb, "  OG 类型", str(og, "type"));
            appendField(sb, "  OG 站点名", str(og, "site_name"));
        }

        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String formatImages(String url, Map<String, Object> data) {
        String actualUrl = str(data, "page_url");
        List<String> imageUrls = (List<String>) data.get("image_urls");

        if (imageUrls == null || imageUrls.isEmpty()) {
            return "网页 \"" + (actualUrl != null ? actualUrl : url) + "\" 中没有提取到图片。";
        }

        List<String> limited = imageUrls.size() > maxImageCount
                ? imageUrls.subList(0, maxImageCount) : imageUrls;

        StringBuilder sb = new StringBuilder();
        sb.append("从网页 \"").append(actualUrl != null ? actualUrl : url)
          .append("\" 提取到 ").append(imageUrls.size()).append(" 张图片");
        if (imageUrls.size() > maxImageCount) {
            sb.append("（仅显示前 ").append(maxImageCount).append(" 张）");
        }
        sb.append("：\n");
        for (int i = 0; i < limited.size(); i++) {
            sb.append(i + 1).append(". ").append(limited.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    // ---- HTTP helpers ----

    private HttpEntity<Void> authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        return new HttpEntity<>(headers);
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
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

    public int getMaxImageCount() {
        return maxImageCount;
    }

    public void setMaxImageCount(int maxImageCount) {
        this.maxImageCount = maxImageCount;
    }
}
