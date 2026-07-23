package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

public class AmapPlaceIdTools implements ToolService.ProjectTool {

    @Override
    public String category() { return "map_navigation"; }

    private static final Logger log = LoggerFactory.getLogger(AmapPlaceIdTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final String AMAP_KEY = "d7db5a1d05aed595cac96d966a7a3471";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final RestTemplate restTemplate;
    private final String privateKey;

    public AmapPlaceIdTools(String privateKey) {
        this(createRestTemplate(), privateKey);
    }

    AmapPlaceIdTools(RestTemplate restTemplate, String privateKey) {
        this.restTemplate = restTemplate;
        this.privateKey = privateKey;
    }

    @Tool(name = "query_amap_place_ids",
          description = "高德地图POI地点搜索。根据关键词/地名/地址查找地点，返回名称、地址、坐标(location)、adcode等。当用户提到具体地名但需要坐标时，先用此工具查询。作为路线规划的第一步，先获取起点和终点的坐标。")
    public String queryPlaceIds(
            @ToolParam(description = "POI keyword, place name, company name, scenic spot, shop, or address to search.")
            String keywords,
            @ToolParam(description = "Optional city name or city adcode to narrow the search. Use an empty string if unknown.")
            String city,
            @ToolParam(description = "Optional Amap POI type or typecode filter. Use an empty string if unknown.")
            String types,
            @ToolParam(description = "Maximum number of POIs to return. Use 10 if unknown.")
            Integer limit) {
        if (keywords == null || keywords.isBlank()) {
            return "\u0049\u0044\u67e5\u8be2\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u8981\u67e5\u8be2\u7684\u5173\u952e\u8bcd\u3002";
        }

        try {
            log.info("amap place search: keywords={}, city={}", keywords, city);
            JsonNode root = requestPlaceText(keywords.trim(), trimToNull(city), trimToNull(types), normalizeLimit(limit));
            String result = formatPlaces(root);
            log.info("amap place search result: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.warn("amap place id tool failed: keywords={}, city={}, types={}, error={}",
                    keywords, city, types, e.getMessage());
            return "\u0049\u0044\u67e5\u8be2\u5931\u8d25\uff1a" + e.getMessage();
        }
    }

    private JsonNode requestPlaceText(String keywords, String city, String types, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(PLACE_TEXT_URL)
                .queryParam("key", AMAP_KEY)
                .queryParam("keywords", keywords)
                .queryParam("offset", limit)
                .queryParam("page", 1)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON");

        if (city != null) {
            builder.queryParam("city", city);
            builder.queryParam("citylimit", true);
        }
        if (types != null) {
            builder.queryParam("types", types);
        }

        JsonNode root = parseJson(restTemplate.getForObject(AmapSignUtil.appendSign(builder, privateKey).build().toUri(), String.class));
        ensureAmapSuccess(root);
        return root;
    }

    private static String formatPlaces(JsonNode root) {
        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.size() == 0) {
            return "\u672a\u67e5\u5230\u5339\u914d\u7684\u9ad8\u5fb7\u5730\u70b9\u0049\u0044\u3002";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\u9ad8\u5fb7\u5730\u70b9\u0049\u0044\u67e5\u8be2\u7ed3\u679c\uff1a\n");
        for (JsonNode poi : pois) {
            sb.append("- ")
                    .append(valueOrUnknown(text(poi.path("name"))))
                    .append("\n  id\uff1a").append(valueOrUnknown(text(poi.path("id"))))
                    .append("\n  \u5730\u5740\uff1a").append(valueOrUnknown(text(poi.path("address"))))
                    .append("\n  \u533a\u57df\uff1a").append(joinRegion(poi))
                    .append("\n  adcode\uff1a").append(valueOrUnknown(text(poi.path("adcode"))))
                    .append("\n  \u5750\u6807\uff1a").append(valueOrUnknown(text(poi.path("location"))))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private static String joinRegion(JsonNode poi) {
        StringBuilder sb = new StringBuilder();
        appendRegion(sb, text(poi.path("pname")));
        appendRegion(sb, text(poi.path("cityname")));
        appendRegion(sb, text(poi.path("adname")));
        return sb.length() == 0 ? "\u672a\u77e5" : sb.toString();
    }

    private static void appendRegion(StringBuilder sb, String value) {
        if (value == null || value.isBlank() || "[]".equals(value)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\uff0c");
        }
        sb.append(value);
    }

    private static JsonNode parseJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("\u9ad8\u5fb7\u63a5\u53e3\u8fd4\u56de\u6570\u636e\u89e3\u6790\u5931\u8d25", e);
        }
    }

    private static void ensureAmapSuccess(JsonNode root) {
        String status = text(root.path("status"));
        if ("1".equals(status)) {
            return;
        }
        String info = text(root.path("info"));
        String infocode = text(root.path("infocode"));
        throw new IllegalStateException("\u9ad8\u5fb7\u63a5\u53e3\u8fd4\u56de\u5931\u8d25"
                + (info != null ? "\uff1a" + info : "")
                + (infocode != null ? "\uff08infocode=" + infocode + "\uff09" : ""));
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String valueOrUnknown(String value) {
        return value != null && !value.isBlank() && !"[]".equals(value) ? value : "\u672a\u77e5";
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
