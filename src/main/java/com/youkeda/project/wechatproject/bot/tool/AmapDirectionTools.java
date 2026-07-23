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

public class AmapDirectionTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(AmapDirectionTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AMAP_KEY = "d7db5a1d05aed595cac96d966a7a3471";
    private static final String WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String BICYCLING_URL = "https://restapi.amap.com/v4/direction/bicycling";
    private static final int MAX_ROUTES = 3;
    private static final int MAX_STEPS = 10;
    private static final int MAX_POLYLINE_POINTS = 60;

    private static final ConcurrentHashMap<String, byte[]> MAP_IMAGE_CACHE = new ConcurrentHashMap<>();

    public static List<byte[]> drainMapImages() {
        List<byte[]> images = new ArrayList<>(MAP_IMAGE_CACHE.values());
        MAP_IMAGE_CACHE.clear();
        return images;
    }

    private final RestTemplate restTemplate;
    private final AmapStaticMapTools staticMapTools;
    private final String privateKey;

    public AmapDirectionTools(String privateKey) {
        this(createRestTemplate(), privateKey);
    }

    AmapDirectionTools(RestTemplate restTemplate, String privateKey) {
        this(restTemplate, new AmapStaticMapTools(privateKey), privateKey);
    }

    AmapDirectionTools(RestTemplate restTemplate, AmapStaticMapTools staticMapTools, String privateKey) {
        this.restTemplate = restTemplate;
        this.staticMapTools = staticMapTools;
        this.privateKey = privateKey;
    }

    @Tool(name = "search_amap_direction_walking",
          description = "Search Amap walking route directions between two coordinates. Use this for walking navigation, pedestrian routes, foot paths. Automatically generates a route map image.")
    public String searchWalking(
            @ToolParam(description = "Starting point coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String origin,
            @ToolParam(description = "Destination coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String destination) {
        return searchDirection(WALKING_URL, "步行", origin, destination, null, 0);
    }

    @Tool(name = "search_amap_direction_transit",
          description = "Search Amap public transit (bus/metro) route directions between two coordinates. Use this for bus, subway, metro, public transportation routes. Requires city parameter. Automatically generates a route map image.")
    public String searchTransit(
            @ToolParam(description = "Starting point coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String origin,
            @ToolParam(description = "Destination coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String destination,
            @ToolParam(description = "City name or adcode for the transit search, e.g. 杭州 or 330100. Required.")
            String city) {
        if (city == null || city.isBlank()) {
            return "公交路线查询失败：请提供城市名称或adcode。";
        }
        return searchDirection(TRANSIT_URL, "公交", origin, destination, city.trim(), 0);
    }

    @Tool(name = "search_amap_direction_driving",
          description = "Search Amap driving route directions between two coordinates. Use this for car navigation, driving routes, vehicle routes. Automatically generates a route map image.")
    public String searchDriving(
            @ToolParam(description = "Starting point coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String origin,
            @ToolParam(description = "Destination coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String destination,
            @ToolParam(description = "Driving strategy: 0=fastest(default), 1=avoid highway, 2=shortest distance, 3=avoid congestion.")
            Integer strategy) {
        int s = (strategy != null && strategy >= 0 && strategy <= 3) ? strategy : 0;
        return searchDirection(DRIVING_URL, "驾车", origin, destination, null, s);
    }

    @Tool(name = "search_amap_direction_bicycling",
          description = "Search Amap bicycling route directions between two coordinates. Use this for cycling, biking navigation, bicycle routes. Automatically generates a route map image.")
    public String searchBicycling(
            @ToolParam(description = "Starting point coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String origin,
            @ToolParam(description = "Destination coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String destination) {
        return searchDirection(BICYCLING_URL, "骑行", origin, destination, null, 0);
    }

    private String searchDirection(String url, String mode, String origin, String destination, String city, int strategy) {
        if (origin == null || origin.isBlank()) {
            return "路线查询失败：请提供起点坐标，格式为经度,纬度。";
        }
        if (destination == null || destination.isBlank()) {
            return "路线查询失败：请提供终点坐标，格式为经度,纬度。";
        }

        String normalizedOrigin = origin.trim();
        String normalizedDest = destination.trim();
        if (!isCoordinate(normalizedOrigin) || !isCoordinate(normalizedDest)) {
            return "路线查询失败：坐标格式需为经度,纬度，例如120.143222,30.236064。";
        }

        try {
            JsonNode root = requestDirection(url, normalizedOrigin, normalizedDest, trimToNull(city), strategy);
            String directionText = formatDirection(root, mode);
            String resultText = appendRouteMap(directionText, root, normalizedOrigin, normalizedDest);
            return resultText;
        } catch (Exception e) {
            log.warn("amap direction tool failed: mode={}, origin={}, destination={}, city={}, error={}",
                    mode, origin, destination, city, e.getMessage());
            return "路线查询失败：" + e.getMessage();
        }
    }

    private JsonNode requestDirection(String url, String origin, String destination, String city, int strategy) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("key", AMAP_KEY)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON");

        if (city != null) {
            builder.queryParam("city", city);
        }
        if (url.equals(DRIVING_URL) && strategy > 0) {
            builder.queryParam("strategy", strategy);
        }

        JsonNode root = parseJson(restTemplate.getForObject(
                AmapSignUtil.appendSign(builder, privateKey).build().encode().toUri(), String.class));
        ensureAmapSuccess(root);
        return root;
    }

    private String appendRouteMap(String directionText, JsonNode root, String origin, String destination) {
        try {
            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (paths.size() == 0) {
                return directionText;
            }

            JsonNode firstPath = paths.get(0);
            String distanceStr = text(firstPath.path("distance"));
            int distance = parseInt(distanceStr, 5000);

            // Build markers: green for origin, red for destination
            String markers = "mid,0x00AA00,起:" + origin + "|mid,0xFF0000,终:" + destination;

            // Collect route polyline for paths overlay
            String pathsParam = null;
            JsonNode steps = firstPath.path("steps");
            if (steps.isArray() && steps.size() > 0) {
                String polyline = collectRoutePolyline(steps);
                if (polyline != null) {
                    pathsParam = "5,0x3366FF,0.8,,:" + polyline;
                }
            }

            String mapText = staticMapTools.generateStaticMap(
                    midpoint(origin, destination),
                    zoomForDistance(distance),
                    "600*400",
                    1,
                    markers,
                    null,
                    pathsParam,
                    false);

            downloadAndCacheMap(mapText);
            return directionText + "\n\n（路线地图已生成，zoom=" + zoomForDistance(distance) + "）";
        } catch (Exception e) {
            log.warn("failed to generate route map: {}", e.getMessage());
            return directionText;
        }
    }

    private void downloadAndCacheMap(String mapText) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(https?://restapi\\.amap\\.com/v3/staticmap\\S+)")
                    .matcher(mapText);
            if (m.find()) {
                String mapUrl = m.group(1);
                byte[] bytes = restTemplate.getForObject(java.net.URI.create(mapUrl), byte[].class);
                if (bytes != null && bytes.length > 0) {
                    MAP_IMAGE_CACHE.put(mapUrl, bytes);
                }
            }
        } catch (Exception e) {
            log.warn("failed to download route map for WeChat: {}", e.getMessage());
        }
    }

    private static String collectRoutePolyline(JsonNode steps) {
        List<String> allPoints = new ArrayList<>();
        for (JsonNode step : steps) {
            String polyline = text(step.path("polyline"));
            if (polyline != null && !polyline.isBlank()) {
                for (String pt : polyline.split(";")) {
                    String trimmed = pt.trim();
                    if (!trimmed.isBlank()) {
                        allPoints.add(trimmed);
                    }
                }
            }
        }

        if (allPoints.isEmpty()) {
            return null;
        }

        int total = allPoints.size();
        if (total <= MAX_POLYLINE_POINTS) {
            return String.join(";", allPoints);
        }

        // Sample evenly to keep URL within limits
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_POLYLINE_POINTS; i++) {
            int idx = (int) ((long) i * total / (MAX_POLYLINE_POINTS - 1));
            if (idx >= total) {
                idx = total - 1;
            }
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(allPoints.get(idx));
        }
        return sb.toString();
    }

    private static String midpoint(String coord1, String coord2) {
        try {
            String[] parts1 = coord1.split(",");
            String[] parts2 = coord2.split(",");
            double lng = (Double.parseDouble(parts1[0]) + Double.parseDouble(parts2[0])) / 2;
            double lat = (Double.parseDouble(parts1[1]) + Double.parseDouble(parts2[1])) / 2;
            return lng + "," + lat;
        } catch (Exception e) {
            return coord1;
        }
    }

    private static int zoomForDistance(int meters) {
        if (meters <= 200) {
            return 17;
        }
        if (meters <= 500) {
            return 16;
        }
        if (meters <= 1000) {
            return 15;
        }
        if (meters <= 2000) {
            return 14;
        }
        if (meters <= 5000) {
            return 13;
        }
        if (meters <= 10000) {
            return 12;
        }
        if (meters <= 25000) {
            return 11;
        }
        return 10;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String formatDirection(JsonNode root, String mode) {
        JsonNode route = root.path("route");
        if (route.isMissingNode() || route.path("paths").size() == 0) {
            return "未找到匹配的" + mode + "路线方案。";
        }

        JsonNode paths = route.path("paths");
        StringBuilder sb = new StringBuilder();
        sb.append("高德").append(mode).append("路线规划结果：\n");

        String originCoord = text(route.path("origin"));
        String destCoord = text(route.path("destination"));
        if (originCoord != null) {
            sb.append("起点：").append(originCoord).append("\n");
        }
        if (destCoord != null) {
            sb.append("终点：").append(destCoord).append("\n");
        }

        int routeIndex = 0;
        for (JsonNode path : paths) {
            if (routeIndex >= MAX_ROUTES) {
                break;
            }
            routeIndex++;

            String distance = text(path.path("distance"));
            String duration = text(path.path("duration"));
            sb.append("\n─── 方案").append(routeIndex);
            if (distance != null) {
                sb.append(" ─ 全程").append(formatDistance(distance));
            }
            if (duration != null) {
                sb.append(" ─ 约").append(formatDuration(duration));
            }
            sb.append(" ───\n");

            formatTransitDetails(sb, path);
            formatSteps(sb, path.path("steps"));
        }

        return sb.toString().trim();
    }

    private static void formatTransitDetails(StringBuilder sb, JsonNode path) {
        String cost = text(path.path("cost"));
        if (cost != null && !"0".equals(cost) && !cost.isBlank()) {
            sb.append("票价：").append(cost).append("元\n");
        }

        String walkingDist = text(path.path("walking_distance"));
        if (walkingDist != null && !"0".equals(walkingDist)) {
            sb.append("步行距离：").append(formatDistance(walkingDist)).append("\n");
        }

        JsonNode transits = path.path("transits");
        if (!transits.isArray() || transits.size() == 0) {
            return;
        }

        sb.append("换乘方案：\n");
        for (JsonNode transit : transits) {
            JsonNode segments = transit.path("segments");
            if (!segments.isArray()) {
                continue;
            }

            for (JsonNode segment : segments) {
                JsonNode walking = segment.path("walking");
                if (!walking.isMissingNode()) {
                    String wDist = text(walking.path("distance"));
                    String wDur = text(walking.path("duration"));
                    if (wDist != null && !"0".equals(wDist)) {
                        sb.append("  走路").append(formatDistance(wDist));
                        if (wDur != null) {
                            sb.append("（约").append(formatDuration(wDur)).append("）");
                        }
                        sb.append("\n");
                    }
                }

                JsonNode bus = segment.path("bus");
                if (!bus.isMissingNode()) {
                    String busName = text(bus.path("name"));
                    String busType = text(bus.path("type"));
                    if (busName != null && !busName.isBlank()) {
                        sb.append("  - ").append(busName);
                        if (busType != null && !busType.isBlank()) {
                            sb.append("（").append(busType).append("）");
                        }
                        sb.append("\n");
                    }
                }

                JsonNode departure = segment.path("departure");
                JsonNode arrival = segment.path("arrival");
                if (!departure.isMissingNode()) {
                    String depName = text(departure.path("name"));
                    if (depName != null && !depName.isBlank()) {
                        sb.append("    上车：").append(depName).append("\n");
                    }
                }
                if (!arrival.isMissingNode()) {
                    String arrName = text(arrival.path("name"));
                    if (arrName != null && !arrName.isBlank()) {
                        sb.append("    下车：").append(arrName).append("\n");
                    }
                }

                String stationCount = text(bus.path("station_num"));
                if (stationCount != null && !"0".equals(stationCount)) {
                    sb.append("    共").append(stationCount).append("站\n");
                }
            }
        }
    }

    private static void formatSteps(StringBuilder sb, JsonNode steps) {
        if (!steps.isArray() || steps.size() == 0) {
            return;
        }

        sb.append("路线步骤：\n");
        int stepCount = 0;
        for (JsonNode step : steps) {
            if (stepCount >= MAX_STEPS) {
                sb.append("  ...（共").append(steps.size()).append("步，仅展示前").append(MAX_STEPS).append("步）\n");
                break;
            }
            String instruction = text(step.path("instruction"));
            if (instruction == null || instruction.isBlank()) {
                continue;
            }
            stepCount++;
            String clean = instruction.replaceAll("<[^>]+>", "");
            sb.append("  ").append(stepCount).append(". ").append(clean);

            String stepDist = text(step.path("distance"));
            String stepDur = text(step.path("duration"));
            if (stepDist != null && !"0".equals(stepDist)) {
                sb.append("（").append(formatDistance(stepDist));
                if (stepDur != null && !"0".equals(stepDur)) {
                    sb.append("，").append(formatDuration(stepDur));
                }
                sb.append("）");
            }
            sb.append("\n");
        }
    }

    private static String formatDuration(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return "未知";
        }
        try {
            int s = Integer.parseInt(seconds);
            if (s < 60) {
                return s + "秒";
            }
            if (s < 3600) {
                return (s / 60) + "分钟";
            }
            int hours = s / 3600;
            int minutes = (s % 3600) / 60;
            if (minutes > 0) {
                return hours + "小时" + minutes + "分钟";
            }
            return hours + "小时";
        } catch (NumberFormatException e) {
            return seconds + "秒";
        }
    }

    private static String formatDistance(String meters) {
        if (meters == null || meters.isBlank()) {
            return "未知";
        }
        try {
            int m = Integer.parseInt(meters);
            if (m < 1000) {
                return m + "米";
            }
            return String.format("%.1f公里", m / 1000.0);
        } catch (NumberFormatException e) {
            return meters + "米";
        }
    }

    private static boolean isCoordinate(String value) {
        return value.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?");
    }

    private static JsonNode parseJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("高德接口返回数据解析失败", e);
        }
    }

    private static void ensureAmapSuccess(JsonNode root) {
        String status = text(root.path("status"));
        if ("1".equals(status)) {
            return;
        }
        String info = text(root.path("info"));
        String infocode = text(root.path("infocode"));
        throw new IllegalStateException("高德接口返回失败"
                + (info != null ? "：" + info : "")
                + (infocode != null ? "（infocode=" + infocode + "）" : ""));
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
