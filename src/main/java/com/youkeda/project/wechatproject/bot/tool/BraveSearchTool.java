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
import java.util.Locale;
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
          description = "【禁止用于地点/餐厅/POI/周边/导航/地图查询】【新闻请用 search_news 工具】搜索互联网获取百科知识、实时事件、专业知识等通用信息。新闻类查询必须使用 search_news 工具，不要用本工具。关键字中严禁包含地点名+推荐/餐厅/美食等组合——这类地点查询必须用高德工具。可用 site:域名 限定网站、filetype:类型 过滤文件。")
    public String webSearch(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("UAPI Search API key not configured");
            return "搜索功能未配置，请联系管理员设置 UAPI Search API Key。";
        }

        String effectiveQuery = query != null ? query.trim() : "";
        if (effectiveQuery.isEmpty()) {
            return "搜索关键词为空，请提供具体的搜索内容。";
        }

        if (shouldUseBrowserAgent(effectiveQuery)) {
            log.info("web_search rejected dynamic platform query, browser agent required: query={}", effectiveQuery);
            return "This is a dynamic in-platform ranking/feed query and must be handled by Browser Agent, not web_search. "
                    + "Use browser_navigate to open the platform page, then browser_get_content/browser_scroll/browser_click "
                    + "to extract the live list. Query: " + effectiveQuery;
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

    private static boolean shouldUseBrowserAgent(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        boolean platform = containsAny(q, List.of(
                "bilibili", "b站", "哔哩", "douyin", "抖音", "xiaohongshu", "小红书",
                "weibo", "微博", "zhihu", "知乎", "youtube", "tiktok"
        ));
        boolean dynamicList = containsAny(q, List.of(
                "今日热门", "热门视频", "热门榜", "热榜", "榜单", "排行榜", "全站排行",
                "实时", "今天", "今日", "最新热门", "trending", "popular", "top"
        ));
        return platform && dynamicList;
    }

    private static boolean containsAny(String text, List<String> terms) {
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
