package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.tools.search")
@ConditionalOnProperty(prefix = "agent.tools.search", name = "enabled", havingValue = "true")
public class BraveSearchTool implements ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchTool.class);

    @Override
    public String category() { return "information"; }
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private boolean enabled = false;
    private String apiKey;
    private String apiUrl = "https://uapis.cn/api/v1/search/aggregate";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    private int resultCount = 5;

    @Tool(name = "web_search",
          description = "搜索互联网获取实时信息。当用户询问的问题需要最新数据、实时信息、或超出你知识范围的事实性内容时调用此工具。你可以在查询中使用 site:域名 限定网站，使用 filetype:类型 过滤文件类型。注意：查询新闻资讯请使用 search_news 工具，不要用此工具搜索新闻。")
    public String webSearch(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("UAPI Search API key not configured");
            return "搜索功能未配置，请联系管理员设置 UAPI Search API Key。";
        }

        String effectiveQuery = query != null ? query.trim() : "";
        if (effectiveQuery.isEmpty()) {
            return "搜索关键词为空，请提供具体的搜索内容。";
        }

        log.info("web_search invoked: query={}", effectiveQuery);

        try {
            RestTemplate restTemplate = buildRestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = Map.of("query", effectiveQuery);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, request, String.class);

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                log.info("web_search returned empty response for query={}", effectiveQuery);
                return "未找到与 \"" + effectiveQuery + "\" 相关的搜索结果。";
            }

            Map<String, Object> parsed = objectMapper.readValue(responseBody,
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
            if (results == null || results.isEmpty()) {
                log.info("web_search returned empty results for query={}", effectiveQuery);
                return "未找到与 \"" + effectiveQuery + "\" 相关的搜索结果。";
            }

            // limit to configured result count
            List<Map<String, Object>> limited = results.size() > resultCount
                    ? results.subList(0, resultCount) : results;

            return formatResults(effectiveQuery, limited);
        } catch (Exception e) {
            log.error("web_search failed for query={}", effectiveQuery, e);
            return "搜索 \"" + effectiveQuery + "\" 时出错：" + e.getMessage() + "。请稍后重试或尝试其他关键词。";
        }
    }

    private String formatResults(String query, List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);
            String title = str(r, "title");
            String url = str(r, "url");
            String snippet = str(r, "snippet");
            String publishTime = str(r, "publish_time");

            sb.append(i + 1).append(". **").append(title).append("**\n");
            sb.append("   链接：").append(url).append("\n");
            if (snippet != null && !snippet.isBlank()) {
                sb.append("   摘要：").append(snippet).append("\n");
            }
            if (publishTime != null && !publishTime.isBlank()) {
                sb.append("   发布时间：").append(publishTime).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
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

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }
}
