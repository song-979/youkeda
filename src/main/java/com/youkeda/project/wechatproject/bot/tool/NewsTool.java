package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class NewsTool implements ToolService.ProjectTool {

    @Override
    public String category() { return "information"; }

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

    @Tool(name = "search_news",
          description = "【新闻专用】搜索最新新闻资讯。当用户询问新闻、热点、最新消息、今日要闻等新闻类内容时，必须优先使用此工具而非 web_search。可按类别筛选：mobile(手机), sports(体育), game(游戏), tech(科技), entertainment(娱乐), finance(财经), auto(汽车), science(科学), education(教育), military(军事), health(健康), travel(旅游), food(美食), house(房产), pet(宠物), other(其他)。不填类别则返回综合新闻。")
    public String searchNews(
            @ToolParam(description = "新闻类别，如 mobile/sports/game/tech 等。不填则返回综合新闻。")
            String type,
            @ToolParam(description = "返回条数，默认10，最大20。")
            Integer pageSize) {
        try {
            int size = normalizePageSize(pageSize);
            String finalType = normalizeType(type);

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
            if (!list.isArray() || list.size() == 0) {
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
        if (VALID_TYPES.contains(trimmed)) {
            return trimmed;
        }
        return null;
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
