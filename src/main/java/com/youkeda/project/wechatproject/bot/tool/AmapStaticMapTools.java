package com.youkeda.project.wechatproject.bot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.util.UriComponentsBuilder;

public class AmapStaticMapTools implements ToolService.ProjectTool {

    @Override
    public String category() { return "location"; }

    private static final String STATIC_MAP_URL = "https://restapi.amap.com/v3/staticmap";
    private static final String AMAP_KEY = "d7db5a1d05aed595cac96d966a7a3471";
    private static final int DEFAULT_ZOOM = 14;
    private static final int MIN_ZOOM = 1;
    private static final int MAX_ZOOM = 17;
    private static final String DEFAULT_SIZE = "600*400";
    private static final int DEFAULT_SCALE = 1;

    private final String privateKey;

    public AmapStaticMapTools(String privateKey) {
        this.privateKey = privateKey;
    }

    @Tool(name = "generate_amap_static_map",
          description = "Use this for Amap/Gaode static map image requests. It generates a static map URL from a longitude,latitude center, optional markers/labels/paths/traffic, and clamps zoom to Amap's 1-17 range. Return the generated URL directly unless this tool returns a failure message.")
    public String generateStaticMap(
            @ToolParam(description = "Map center coordinate in Amap longitude,latitude format, e.g. 120.143222,30.236064.")
            String location,
            @ToolParam(description = "Map zoom level from 1 to 17. Use 14 if unknown.")
            Integer zoom,
            @ToolParam(description = "Image size in width*height format, e.g. 600*400. Use 600*400 if unknown.")
            String size,
            @ToolParam(description = "Image scale, usually 1 or 2. Use 1 if unknown.")
            Integer scale,
            @ToolParam(description = "Optional Amap marker expression, e.g. mid,,A:120.143222,30.236064. Use an empty string to place a default marker at the center.")
            String markers,
            @ToolParam(description = "Optional Amap labels expression. Use an empty string if no labels are needed.")
            String labels,
            @ToolParam(description = "Optional Amap paths expression. Use an empty string if no paths are needed.")
            String paths,
            @ToolParam(description = "Whether to show traffic. Use false if unknown.")
            Boolean traffic) {
        if (location == null || location.isBlank()) {
            return "\u9759\u6001\u5730\u56fe\u751f\u6210\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u4e2d\u5fc3\u70b9\u5750\u6807\uff0c\u683c\u5f0f\u4e3a\u7ecf\u5ea6,\u7eac\u5ea6\u3002";
        }

        String normalizedLocation = location.trim();
        if (!isCoordinate(normalizedLocation)) {
            return "\u9759\u6001\u5730\u56fe\u751f\u6210\u5931\u8d25\uff1a\u5750\u6807\u683c\u5f0f\u9700\u4e3a\u7ecf\u5ea6,\u7eac\u5ea6\uff0c\u4f8b\u5982120.143222,30.236064\u3002";
        }

        String normalizedMarkers = trimToNull(markers);
        if (normalizedMarkers == null) {
            normalizedMarkers = "mid,,A:" + normalizedLocation;
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(STATIC_MAP_URL)
                .queryParam("key", AMAP_KEY)
                .queryParam("location", normalizedLocation)
                .queryParam("zoom", normalizeZoom(zoom))
                .queryParam("size", normalizeSize(size))
                .queryParam("scale", normalizeScale(scale))
                .queryParam("markers", normalizedMarkers)
                .queryParamIfPresent("labels", java.util.Optional.ofNullable(trimToNull(labels)))
                .queryParamIfPresent("paths", java.util.Optional.ofNullable(trimToNull(paths)))
                .queryParam("traffic", Boolean.TRUE.equals(traffic) ? 1 : 0);

        String url = AmapSignUtil.appendSign(builder, privateKey).build().encode().toUriString();

        return """
                \u9ad8\u5fb7\u9759\u6001\u5730\u56fe\uff1a
                %s
                """.formatted(url).trim();
    }

    private static boolean isCoordinate(String value) {
        return value.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?");
    }

    private static int normalizeZoom(Integer zoom) {
        if (zoom == null) {
            return DEFAULT_ZOOM;
        }
        return Math.max(MIN_ZOOM, Math.min(zoom, MAX_ZOOM));
    }

    private static String normalizeSize(String size) {
        String normalized = trimToNull(size);
        if (normalized == null) {
            return DEFAULT_SIZE;
        }
        return normalized.matches("\\d{2,4}\\*\\d{2,4}") ? normalized : DEFAULT_SIZE;
    }

    private static int normalizeScale(Integer scale) {
        return scale != null && scale == 2 ? 2 : DEFAULT_SCALE;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
