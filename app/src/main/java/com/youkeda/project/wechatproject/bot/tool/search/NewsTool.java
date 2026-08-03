package com.youkeda.project.wechatproject.bot.tool.search;

import com.youkeda.project.wechatproject.bot.tool.ToolService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class NewsTool implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(NewsTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NEWS_API_URL = "https://apis.juhe.cn/fapigw/aibrief/list";
    private static final String API_KEY = "b6a308ba23f8de51a295d88a852b2792";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    private static final List<String> VALID_TYPES = List.of(
            "mobile", "sports", "game", "tech", "entertainment",
            "finance", "auto", "science", "education", "military",
            "health", "travel", "food", "house", "pet", "other"
    );

    private final RestTemplate restTemplate;

    public NewsTool() {
        this.restTemplate = new RestTemplate();
    }

    NewsTool(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String category() {
        return "information";
    }

    @Tool(name = "search_news",
            description = "【新闻专用】查询最新新闻资讯。用户询问新闻、热点、最新消息、今日要闻时必须优先调用本工具；科技新闻使用 type=tech。可选类别：mobile, sports, game, tech, entertainment, finance, auto, science, education, military, health, travel, food, house, pet, other。")
    public String searchNews(
            @ToolParam(description = "新闻类别，例如 tech 表示科技新闻；不传则返回综合新闻。")
            String type,
            @ToolParam(description = "返回条数，默认 10，最大 20。")
            Integer pageSize) {
        try {
            int size = normalizePageSize(pageSize);
            String finalType = normalizeType(type);

            log.info("search_news invoked: type={}, pageSize={}", finalType, size);

            StringBuilder url = new StringBuilder(NEWS_API_URL)
                    .append("?key=").append(API_KEY)
                    .append("&page_size=").append(size);
            if (finalType != null) {
                url.append("&type=").append(finalType);
            }

            String responseBody = restTemplate.getForObject(url.toString(), String.class);
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);

            int errorCode = root.path("error_code").asInt(-1);
            if (errorCode != 0) {
                String reason = root.path("reason").asText("未知错误");
                return "新闻查询失败：" + reason + "（error_code=" + errorCode + "）";
            }

            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return "未找到匹配的新闻资讯。";
            }

            return formatNews(list, finalType);
        } catch (Exception e) {
            log.warn("news tool failed: type={}, pageSize={}, error={}", type, pageSize, e.getMessage());
            return "新闻查询失败：" + e.getMessage();
        }
    }

    private static String formatNews(JsonNode list, String type) {
        StringBuilder sb = new StringBuilder();
        String categoryLabel = type != null ? type + "类" : "综合";
        sb.append("最新").append(categoryLabel).append("新闻：\n\n");

        int index = 0;
        for (JsonNode item : list) {
            index++;
            String title = text(item.path("title"));
            String author = text(item.path("author_name"));
            String publishDate = text(item.path("publish_date"));
            String summary = text(item.path("summary"));
            String url = text(item.path("url"));

            sb.append(index).append(". ").append(title).append("\n");
            if (author != null) {
                sb.append("   来源：").append(author);
            }
            if (publishDate != null) {
                sb.append("   ").append(publishDate);
            }
            sb.append("\n");
            if (summary != null) {
                sb.append("   ").append(summary).append("\n");
            }
            if (url != null) {
                sb.append("   链接：").append(url).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String trimmed = type.trim().toLowerCase();
        return VALID_TYPES.contains(trimmed) ? trimmed : null;
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static String text(JsonNode node) {
        return node != null && !node.isMissingNode() && node.isTextual() ? node.asText() : null;
    }
}
