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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class AmapAroundSearchTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(AmapAroundSearchTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final String AMAP_KEY = "d7db5a1d05aed595cac96d966a7a3471";
    private static final int DEFAULT_RADIUS = 1000;
    private static final int MAX_RADIUS = 50000;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_MAP_POI_MARKERS = 9;

    private static final ConcurrentHashMap<String, byte[]> MAP_IMAGE_CACHE = new ConcurrentHashMap<>();

    public static List<byte[]> drainMapImages() {
        List<byte[]> images = new ArrayList<>(MAP_IMAGE_CACHE.values());
        MAP_IMAGE_CACHE.clear();
        return images;
    }

    private final RestTemplate restTemplate;
    private final AmapStaticMapTools staticMapTools;
    private final String privateKey;

    public AmapAroundSearchTools(String privateKey) {
        this(createRestTemplate(), privateKey);
    }

    AmapAroundSearchTools(RestTemplate restTemplate, String privateKey) {
        this(restTemplate, new AmapStaticMapTools(privateKey), privateKey);
    }

    AmapAroundSearchTools(RestTemplate restTemplate, AmapStaticMapTools staticMapTools, String privateKey) {
        this.restTemplate = restTemplate;
        this.staticMapTools = staticMapTools;
        this.privateKey = privateKey;
    }

    @Tool(name = "search_amap_places_around",
          description = "Use this for Amap/Gaode nearby, around, radius, restaurant, hotel, parking, POI searches near a coordinate. This tool calls Amap place/around and automatically appends a static map URL using a zoom level adjusted from the search radius. Return the tool result directly unless it says the search failed.")
    public String searchPlacesAround(
            @ToolParam(description = "Center coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String location,
            @ToolParam(description = "Optional POI keyword, such as restaurant, hotel, parking, Starbucks. Use an empty string if unknown.")
            String keywords,
            @ToolParam(description = "Optional Amap POI type or typecode filter. Use an empty string if unknown.")
            String types,
            @ToolParam(description = "Search radius in meters. Use 1000 if unknown. Maximum is 50000.")
            Integer radius,
            @ToolParam(description = "Maximum number of POIs to return. Use 10 if unknown.")
            Integer limit) {
        if (location == null || location.isBlank()) {
            return "\u5468\u8fb9\u641c\u7d22\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u4e2d\u5fc3\u70b9\u5750\u6807\uff0c\u683c\u5f0f\u4e3a\u7ecf\u5ea6,\u7eac\u5ea6\u3002";
        }

        String normalizedLocation = location.trim();
        if (!normalizedLocation.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
            return "\u5468\u8fb9\u641c\u7d22\u5931\u8d25\uff1a\u5750\u6807\u683c\u5f0f\u9700\u4e3a\u7ecf\u5ea6,\u7eac\u5ea6\uff0c\u4f8b\u5982120.143222,30.236064\u3002";
        }

        try {
            int normalizedRadius = normalizeRadius(radius);
            JsonNode root = requestPlaceAround(
                    normalizedLocation,
                    trimToNull(keywords),
                    trimToNull(types),
                    normalizedRadius,
                    normalizeLimit(limit));
            return appendStaticMap(formatPlaces(root), root, normalizedLocation, normalizedRadius);
        } catch (Exception e) {
            log.warn("amap around search tool failed: location={}, keywords={}, types={}, radius={}, error={}",
                    normalizedLocation, keywords, types, radius, e.getMessage());
            return "\u5468\u8fb9\u641c\u7d22\u5931\u8d25\uff1a" + e.getMessage();
        }
    }

    private JsonNode requestPlaceAround(String location, String keywords, String types, int radius, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(PLACE_AROUND_URL)
                .queryParam("key", AMAP_KEY)
                .queryParam("location", location)
                .queryParam("radius", radius)
                .queryParam("offset", limit)
                .queryParam("page", 1)
                .queryParam("sortrule", "distance")
                .queryParam("extensions", "base")
                .queryParam("output", "JSON");

        if (keywords != null) {
            builder.queryParam("keywords", keywords);
        }
        if (types != null) {
            builder.queryParam("types", types);
        }

        JsonNode root = parseJson(restTemplate.getForObject(AmapSignUtil.appendSign(builder, privateKey).build().encode().toUri(), String.class));
        ensureAmapSuccess(root);
        return root;
    }

    private static String formatPlaces(JsonNode root) {
        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.size() == 0) {
            return "\u672a\u67e5\u5230\u5339\u914d\u7684\u5468\u8fb9\u5730\u70b9\u3002";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\u9ad8\u5fb7\u5468\u8fb9\u641c\u7d22\u7ed3\u679c\uff1a\n");
        for (JsonNode poi : pois) {
            sb.append("- ")
                    .append(valueOrUnknown(text(poi.path("name"))))
                    .append("\n  id\uff1a").append(valueOrUnknown(text(poi.path("id"))))
                    .append("\n  \u8ddd\u79bb\uff1a").append(valueOrUnknown(text(poi.path("distance")))).append("\u7c73")
                    .append("\n  \u5730\u5740\uff1a").append(valueOrUnknown(text(poi.path("address"))))
                    .append("\n  \u533a\u57df\uff1a").append(joinRegion(poi))
                    .append("\n  adcode\uff1a").append(valueOrUnknown(text(poi.path("adcode"))))
                    .append("\n  \u5750\u6807\uff1a").append(valueOrUnknown(text(poi.path("location"))))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String appendStaticMap(String placesText, JsonNode root, String location, int radius) {
        String mapText = staticMapTools.generateStaticMap(
                location,
                zoomForRadius(radius),
                "600*400",
                1,
                markersForMap(location, root.path("pois")),
                null,
                null,
                false);

        // Download the map image so WeChat can display it as an actual image
        downloadAndCacheMap(mapText);

        return placesText + "\n\n\uff08\u9759\u6001\u5730\u56fe\u5df2\u751f\u6210\uff0czoom=" + zoomForRadius(radius) + "\uff09";
    }

    private void downloadAndCacheMap(String mapText) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(https?://restapi\\.amap\\.com/v3/staticmap\\S+)")
                    .matcher(mapText);
            if (m.find()) {
                String url = m.group(1);
                byte[] bytes = restTemplate.getForObject(java.net.URI.create(url), byte[].class);
                if (bytes != null && bytes.length > 0) {
                    MAP_IMAGE_CACHE.put(url, bytes);
                }
            }
        } catch (Exception e) {
            log.warn("failed to download static map for WeChat: {}", e.getMessage());
        }
    }

    private static int zoomForRadius(int radius) {
        if (radius <= 300) {
            return 17;
        }
        if (radius <= 500) {
            return 16;
        }
        if (radius <= 1000) {
            return 15;
        }
        if (radius <= 2000) {
            return 14;
        }
        if (radius <= 5000) {
            return 13;
        }
        if (radius <= 10000) {
            return 12;
        }
        if (radius <= 25000) {
            return 11;
        }
        return 10;
    }

    private static String markersForMap(String centerLocation, JsonNode pois) {
        StringBuilder markers = new StringBuilder("large,0xFF0000,A:").append(centerLocation);
        if (!pois.isArray() || pois.size() == 0) {
            return markers.toString();
        }
        int markerIndex = 1;
        for (JsonNode poi : pois) {
            if (markerIndex > MAX_MAP_POI_MARKERS) {
                break;
            }
            String poiLocation = text(poi.path("location"));
            if (poiLocation == null || poiLocation.isBlank()) {
                continue;
            }
            markers.append("|mid,0x008000,").append(markerIndex).append(":").append(poiLocation);
            markerIndex++;
        }
        return markers.toString();
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

    private static int normalizeRadius(Integer radius) {
        if (radius == null || radius < 1) {
            return DEFAULT_RADIUS;
        }
        return Math.min(radius, MAX_RADIUS);
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
