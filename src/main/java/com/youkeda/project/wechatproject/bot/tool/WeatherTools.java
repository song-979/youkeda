package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Locale;
import java.util.Map;

public class WeatherTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    @Override
    public String category() { return "information"; }
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DISTRICT_URL = "https://restapi.amap.com/v3/config/district";
    private static final String WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final Map<String, String> LOCATION_ALIASES = Map.ofEntries(
            Map.entry("hangzhou", "\u676d\u5dde"),
            Map.entry("beijing", "\u5317\u4eac"),
            Map.entry("shanghai", "\u4e0a\u6d77"),
            Map.entry("guangzhou", "\u5e7f\u5dde"),
            Map.entry("shenzhen", "\u6df1\u5733"),
            Map.entry("nanjing", "\u5357\u4eac"),
            Map.entry("suzhou", "\u82cf\u5dde"),
            Map.entry("chengdu", "\u6210\u90fd"),
            Map.entry("wuhan", "\u6b66\u6c49"),
            Map.entry("xian", "\u897f\u5b89"),
            Map.entry("\u897f\u6e56\u533a", "330106"),
            Map.entry("\u676d\u5dde\u5e02\u897f\u6e56\u533a", "330106"),
            Map.entry("\u676d\u5dde\u897f\u6e56\u533a", "330106")
    );

    private final RestTemplate restTemplate;
    private final WeatherProperties properties;

    public WeatherTools(WeatherProperties properties) {
        this(properties, createRestTemplate());
    }

    WeatherTools(WeatherProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Tool(name = "get_current_weather",
          description = "Get current weather by city, district, or adcode.")
    public String getCurrentWeather(
            @ToolParam(description = "City, district, or adcode. Prefer complete Chinese location names, e.g. Hangzhou, 330106.")
            String location) {
        if (location == null || location.isBlank()) {
            return "\u5929\u6c14\u67e5\u8be2\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u57ce\u5e02\u6216\u5730\u70b9\u540d\u79f0\u3002";
        }
        if (properties.getAmapKey() == null || properties.getAmapKey().isBlank()) {
            return "\u5929\u6c14\u67e5\u8be2\u5931\u8d25\uff1a\u672a\u914d\u7f6e\u9ad8\u5fb7 Web \u670d\u52a1 API Key\uff0c\u8bf7\u914d\u7f6e agent.tools.weather.amap-key\u3002";
        }

        try {
            String normalizedLocation = normalizeLocation(location);
            CurrentWeather weather = fetchWeather(normalizedLocation);
            return formatWeather(weather);
        } catch (Exception e) {
            log.warn("weather tool failed: location={}, error={}", location, e.getMessage());
            return "\u5929\u6c14\u67e5\u8be2\u5931\u8d25\uff1a" + e.getMessage();
        }
    }

    @Tool(name = "get_weather_forecast",
          description = "Get weather forecast by city, district, or adcode. Returns today and the next 3 days. Use this for tomorrow, future, next few days, or forecast requests.")
    public String getWeatherForecast(
            @ToolParam(description = "City, district, or adcode. Prefer complete Chinese location names, e.g. Hangzhou, 330106.")
            String location) {
        if (location == null || location.isBlank()) {
            return "\u5929\u6c14\u9884\u62a5\u67e5\u8be2\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u57ce\u5e02\u6216\u5730\u70b9\u540d\u79f0\u3002";
        }
        if (properties.getAmapKey() == null || properties.getAmapKey().isBlank()) {
            return "\u5929\u6c14\u9884\u62a5\u67e5\u8be2\u5931\u8d25\uff1a\u672a\u914d\u7f6e\u9ad8\u5fb7 Web \u670d\u52a1 API Key\uff0c\u8bf7\u914d\u7f6e agent.tools.weather.amap-key\u3002";
        }

        try {
            String normalizedLocation = normalizeLocation(location);
            ForecastWeather forecast = fetchForecast(normalizedLocation);
            return formatForecast(forecast);
        } catch (Exception e) {
            log.warn("weather forecast tool failed: location={}, error={}", location, e.getMessage());
            return "\u5929\u6c14\u9884\u62a5\u67e5\u8be2\u5931\u8d25\uff1a" + e.getMessage();
        }
    }

    private static String normalizeLocation(String location) {
        String trimmed = location == null ? "" : location.trim();
        if (trimmed.matches("\\d{6}")) {
            return trimmed;
        }
        String alias = LOCATION_ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
        return alias != null ? alias : trimmed;
    }

    private CurrentWeather fetchWeather(String location) {
        JsonNode root = requestWeather(location, "base");
        JsonNode live = chooseByLocation(root.path("lives"), location);
        if (isMissing(live)) {
            String adcode = resolveAdcode(location);
            root = requestWeather(adcode, "base");
            live = chooseByLocation(root.path("lives"), adcode);
        }
        if (isMissing(live)) {
            throw new IllegalStateException("\u9ad8\u5fb7\u5929\u6c14\u670d\u52a1\u6ca1\u6709\u8fd4\u56de\u5b9e\u51b5\u5929\u6c14\u6570\u636e");
        }

        return new CurrentWeather(
                text(live.path("province")),
                text(live.path("city")),
                text(live.path("adcode")),
                text(live.path("weather")),
                text(live.path("temperature")),
                text(live.path("winddirection")),
                text(live.path("windpower")),
                text(live.path("humidity")),
                text(live.path("reporttime"))
        );
    }

    private ForecastWeather fetchForecast(String location) {
        JsonNode root = requestWeather(location, "all");
        JsonNode forecast = chooseByLocation(root.path("forecasts"), location);
        if (isMissing(forecast)) {
            String adcode = resolveAdcode(location);
            root = requestWeather(adcode, "all");
            forecast = chooseByLocation(root.path("forecasts"), adcode);
        }
        if (isMissing(forecast)) {
            throw new IllegalStateException("\u9ad8\u5fb7\u5929\u6c14\u670d\u52a1\u6ca1\u6709\u8fd4\u56de\u5929\u6c14\u9884\u62a5\u6570\u636e");
        }

        JsonNode casts = forecast.path("casts");
        if (!casts.isArray() || casts.size() == 0) {
            throw new IllegalStateException("\u9ad8\u5fb7\u5929\u6c14\u670d\u52a1\u8fd4\u56de\u7684\u5929\u6c14\u9884\u62a5\u4e3a\u7a7a");
        }

        return new ForecastWeather(
                text(forecast.path("province")),
                text(forecast.path("city")),
                text(forecast.path("adcode")),
                text(forecast.path("reporttime")),
                casts
        );
    }

    private JsonNode requestWeather(String city, String extensions) {
        String url = UriComponentsBuilder.fromUriString(WEATHER_URL)
                .queryParam("key", properties.getAmapKey())
                .queryParam("city", city)
                .queryParam("extensions", extensions)
                .queryParam("output", "JSON")
                .build().encode().toUriString();

        JsonNode root = parseJson(restTemplate.getForObject(url, String.class));
        ensureAmapSuccess(root);
        return root;
    }

    private String resolveAdcode(String location) {
        if (location.matches("\\d{6}")) {
            return location;
        }

        String url = UriComponentsBuilder.fromUriString(DISTRICT_URL)
                .queryParam("key", properties.getAmapKey())
                .queryParam("keywords", location)
                .queryParam("subdistrict", 0)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON")
                .build().encode().toUriString();

        JsonNode root = parseJson(restTemplate.getForObject(url, String.class));
        ensureAmapSuccess(root);

        JsonNode first = root.path("districts").path(0);
        String adcode = text(first.path("adcode"));
        if (adcode == null || adcode.isBlank()) {
            throw new IllegalStateException("\u6ca1\u6709\u627e\u5230\u5730\u70b9\u201c" + location + "\u201d\u5bf9\u5e94\u7684 adcode");
        }
        return adcode;
    }

    private static JsonNode chooseByLocation(JsonNode items, String requestedLocation) {
        if (!items.isArray() || items.size() == 0) {
            return items.path(0);
        }
        for (JsonNode item : items) {
            if (matchesRequestedLocation(item, requestedLocation)) {
                return item;
            }
        }
        return items.path(0);
    }

    private static boolean matchesRequestedLocation(JsonNode item, String requestedLocation) {
        String province = text(item.path("province"));
        String city = text(item.path("city"));
        String adcode = text(item.path("adcode"));
        return requestedLocation != null && (requestedLocation.equals(adcode)
                || ("330106".equals(requestedLocation) && "330106".equals(adcode))
                || ("\u897f\u6e56\u533a".equals(requestedLocation) && "\u6d59\u6c5f".equals(province) && "\u897f\u6e56\u533a".equals(city))
                || ("\u676d\u5dde".equals(requestedLocation) && ("330100".equals(adcode) || "\u676d\u5dde\u5e02".equals(city)))
                || ("\u676d\u5dde\u5e02".equals(requestedLocation) && ("330100".equals(adcode) || "\u676d\u5dde\u5e02".equals(city))));
    }

    private static boolean isMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
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
        if ("10009".equals(infocode) || "USERKEY_PLAT_NOMATCH".equals(info)) {
            throw new IllegalStateException("\u9ad8\u5fb7 Key \u5e73\u53f0\u7c7b\u578b\u4e0d\u5339\u914d\uff1a\u5f53\u524d key \u4e0d\u80fd\u8c03\u7528 Web \u670d\u52a1\u5929\u6c14\u63a5\u53e3\uff0c\u8bf7\u5728\u9ad8\u5fb7\u5f00\u653e\u5e73\u53f0\u521b\u5efa\u6216\u5207\u6362\u4e3a Web \u670d\u52a1\u7c7b\u578b Key\uff08infocode=10009\uff09");
        }
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

    private static String formatWeather(CurrentWeather weather) {
        return """
                \u5730\u70b9\uff1a%s%s
                adcode\uff1a%s
                \u53d1\u5e03\u65f6\u95f4\uff1a%s
                \u5929\u6c14\uff1a%s
                \u5b9e\u65f6\u6c14\u6e29\uff1a%s\u00b0C
                \u98ce\u5411\uff1a%s
                \u98ce\u529b\uff1a%s\u7ea7
                \u7a7a\u6c14\u6e7f\u5ea6\uff1a%s%%
                """.formatted(
                valueOrUnknown(weather.province()),
                weather.city() != null && !weather.city().isBlank() ? "\uff0c" + weather.city() : "",
                valueOrUnknown(weather.adcode()),
                valueOrUnknown(weather.reportTime()),
                valueOrUnknown(weather.weather()),
                valueOrUnknown(weather.temperature()),
                valueOrUnknown(weather.windDirection()),
                valueOrUnknown(weather.windPower()),
                valueOrUnknown(weather.humidity())
        ).trim();
    }

    private static String formatForecast(ForecastWeather forecast) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u5730\u70b9\uff1a")
                .append(valueOrUnknown(forecast.province()))
                .append(forecast.city() != null && !forecast.city().isBlank() ? "\uff0c" + forecast.city() : "")
                .append("\n");
        sb.append("adcode\uff1a").append(valueOrUnknown(forecast.adcode())).append("\n");
        sb.append("\u53d1\u5e03\u65f6\u95f4\uff1a").append(valueOrUnknown(forecast.reportTime())).append("\n");
        sb.append("\u5929\u6c14\u9884\u62a5\uff1a\n");

        for (JsonNode cast : forecast.casts()) {
            sb.append("- ")
                    .append(valueOrUnknown(text(cast.path("date"))))
                    .append("\uff08\u5468").append(valueOrUnknown(text(cast.path("week")))).append("\uff09\uff1a")
                    .append("\u767d\u5929").append(valueOrUnknown(text(cast.path("dayweather"))))
                    .append("\uff0c\u591c\u95f4").append(valueOrUnknown(text(cast.path("nightweather"))))
                    .append("\uff0c").append(valueOrUnknown(text(cast.path("nighttemp"))))
                    .append("~").append(valueOrUnknown(text(cast.path("daytemp")))).append("\u00b0C")
                    .append("\uff0c\u767d\u5929").append(valueOrUnknown(text(cast.path("daywind"))))
                    .append("\u98ce").append(valueOrUnknown(text(cast.path("daypower")))).append("\u7ea7")
                    .append("\uff0c\u591c\u95f4").append(valueOrUnknown(text(cast.path("nightwind"))))
                    .append("\u98ce").append(valueOrUnknown(text(cast.path("nightpower")))).append("\u7ea7")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private static String valueOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : "\u672a\u77e5";
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private record CurrentWeather(String province, String city, String adcode, String weather,
                                  String temperature, String windDirection, String windPower,
                                  String humidity, String reportTime) {
    }

    private record ForecastWeather(String province, String city, String adcode,
                                   String reportTime, JsonNode casts) {
    }

    @ConfigurationProperties(prefix = "agent.tools.weather")
    public static class WeatherProperties {
        private String amapKey;

        public String getAmapKey() {
            return amapKey;
        }

        public void setAmapKey(String amapKey) {
            this.amapKey = amapKey;
        }
    }
}
