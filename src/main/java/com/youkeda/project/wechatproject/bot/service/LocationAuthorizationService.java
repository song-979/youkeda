package com.youkeda.project.wechatproject.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocationAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(LocationAuthorizationService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AMAP_REVERSE_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String AMAP_PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final Duration DEFAULT_LOCATION_TTL = Duration.ofMinutes(30);

    private final RestTemplate restTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, PendingAuthorization> pendingAuthorizations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthorizedLocation> latestLocations = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final Duration tokenTtl;
    private final Duration locationTtl;
    private final String amapKey;

    @Autowired
    public LocationAuthorizationService(
            @Value("${location.auth.base-url:}") String baseUrl,
            @Value("${location.auth.token-ttl-minutes:10}") long tokenTtlMinutes,
            @Value("${location.auth.location-ttl-minutes:30}") long locationTtlMinutes,
            @Value("${agent.tools.weather.amap-key:}") String amapKey) {
        this(baseUrl, tokenTtlMinutes, locationTtlMinutes, amapKey, createRestTemplate());
    }

    LocationAuthorizationService(String baseUrl,
                                 long tokenTtlMinutes,
                                 long locationTtlMinutes,
                                 String amapKey,
                                 RestTemplate restTemplate) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.tokenTtl = Duration.ofMinutes(Math.max(1, tokenTtlMinutes));
        this.locationTtl = Duration.ofMinutes(Math.max(1, locationTtlMinutes));
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.restTemplate = restTemplate;
    }

    public String createAuthorizationUrl(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("无法识别当前用户，不能发起定位授权。");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置 location.auth.base-url，暂时无法生成定位授权链接。");
        }

        evictExpired();
        String token = generateToken();
        pendingAuthorizations.put(token, new PendingAuthorization(userId.trim(), Instant.now().plus(tokenTtl)));
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/location/auth")
                .queryParam("token", token)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        PendingAuthorization pending = pendingAuthorizations.get(token.trim());
        if (pending == null) {
            return false;
        }
        if (pending.isExpired()) {
            pendingAuthorizations.remove(token.trim());
            return false;
        }
        return true;
    }

    public AuthorizedLocation completeAuthorization(String token,
                                                    double longitude,
                                                    double latitude,
                                                    Double accuracyMeters) {
        if (Double.isNaN(longitude) || Double.isNaN(latitude)) {
            throw new IllegalArgumentException("经纬度不能为空。");
        }

        evictExpired();
        String normalizedToken = token == null ? "" : token.trim();
        PendingAuthorization pending = pendingAuthorizations.get(normalizedToken);
        if (pending == null || pending.isExpired()) {
            throw new IllegalStateException("定位授权已失效，请重新获取新的授权链接。");
        }

        ReverseGeocodeResult geocode = reverseGeocode(longitude, latitude);
        AuthorizedLocation location = new AuthorizedLocation(
                pending.userId(),
                longitude,
                latitude,
                accuracyMeters,
                geocode.address(),
                geocode.poiName(),
                geocode.adcode(),
                geocode.city(),
                geocode.province(),
                Instant.now()
        );
        latestLocations.put(pending.userId(), location);
        return location;
    }

    public AuthorizedLocation adjustAuthorizedLocation(String token, String keyword) {
        String normalizedToken = token == null ? "" : token.trim();
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isBlank()) {
            throw new IllegalArgumentException("请填写要修正的位置名称或地址。");
        }

        evictExpired();
        PendingAuthorization pending = pendingAuthorizations.get(normalizedToken);
        if (pending == null || pending.isExpired()) {
            throw new IllegalStateException("定位授权已失效，请重新获取新的授权链接。");
        }
        if (amapKey.isBlank()) {
            throw new IllegalStateException("未配置高德 Web 服务 key，暂时无法按地址修正位置。");
        }

        AuthorizedLocation current = latestLocations.get(pending.userId());
        PlaceSearchResult place = searchPlace(normalizedKeyword, current);
        AuthorizedLocation adjusted = new AuthorizedLocation(
                pending.userId(),
                place.longitude(),
                place.latitude(),
                null,
                place.address(),
                place.poiName(),
                place.adcode(),
                place.city(),
                place.province(),
                Instant.now()
        );
        latestLocations.put(pending.userId(), adjusted);
        return adjusted;
    }

    public Optional<AuthorizedLocation> getLatestLocation(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        evictExpired();
        AuthorizedLocation location = latestLocations.get(userId.trim());
        if (location == null || location.isExpired(locationTtl)) {
            latestLocations.remove(userId.trim());
            return Optional.empty();
        }
        return Optional.of(location);
    }

    private ReverseGeocodeResult reverseGeocode(double longitude, double latitude) {
        if (amapKey.isBlank()) {
            return new ReverseGeocodeResult("", "", "", "", "");
        }

        try {
            String url = UriComponentsBuilder.fromUriString(AMAP_REVERSE_GEOCODE_URL)
                    .queryParam("key", amapKey)
                    .queryParam("location", longitude + "," + latitude)
                    .queryParam("extensions", "all")
                    .queryParam("radius", 200)
                    .queryParam("output", "JSON")
                    .build()
                    .encode()
                    .toUriString();

            JsonNode root = parseJson(restTemplate.getForObject(url, String.class));
            ensureAmapSuccess(root);
            JsonNode regeocode = root.path("regeocode");
            JsonNode component = regeocode.path("addressComponent");
            JsonNode firstPoi = regeocode.path("pois").path(0);

            String city = text(component.path("city"));
            if (city.isBlank()) {
                city = text(component.path("district"));
            }
            return new ReverseGeocodeResult(
                    text(regeocode.path("formatted_address")),
                    text(firstPoi.path("name")),
                    text(component.path("adcode")),
                    city,
                    text(component.path("province"))
            );
        } catch (Exception e) {
            log.warn("reverse geocode failed: lng={}, lat={}, error={}", longitude, latitude, e.getMessage());
            return new ReverseGeocodeResult("", "", "", "", "");
        }
    }

    private PlaceSearchResult searchPlace(String keyword, AuthorizedLocation current) {
        String cityHint = "";
        if (current != null) {
            if (current.adcode() != null && !current.adcode().isBlank()) {
                cityHint = current.adcode();
            } else if (current.city() != null && !current.city().isBlank()) {
                cityHint = current.city();
            }
        }

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(AMAP_PLACE_TEXT_URL)
                    .queryParam("key", amapKey)
                    .queryParam("keywords", keyword)
                    .queryParam("offset", 1)
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .queryParam("output", "JSON");
            if (!cityHint.isBlank()) {
                builder.queryParam("city", cityHint);
                builder.queryParam("citylimit", true);
            }

            JsonNode root = parseJson(restTemplate.getForObject(builder.build().encode().toUri(), String.class));
            ensureAmapSuccess(root);
            JsonNode poi = root.path("pois").path(0);
            String location = text(poi.path("location"));
            if (location.isBlank() || !location.contains(",")) {
                throw new IllegalStateException("没有找到可用的坐标结果。");
            }
            String[] parts = location.split(",", 2);
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);
            return new PlaceSearchResult(
                    longitude,
                    latitude,
                    text(poi.path("name")),
                    text(poi.path("address")),
                    text(poi.path("adcode")),
                    text(poi.path("cityname")),
                    text(poi.path("pname"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("按地址修正位置失败：" + e.getMessage(), e);
        }
    }

    private void evictExpired() {
        pendingAuthorizations.entrySet().removeIf(entry -> entry.getValue().isExpired());
        latestLocations.entrySet().removeIf(entry -> entry.getValue().isExpired(locationTtl));
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(5000);
        return new RestTemplate(requestFactory);
    }

    private static JsonNode parseJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("高德接口返回数据解析失败", e);
        }
    }

    private static void ensureAmapSuccess(JsonNode root) {
        if ("1".equals(text(root.path("status")))) {
            return;
        }
        throw new IllegalStateException("高德接口调用失败: "
                + text(root.path("info"))
                + " (infocode=" + text(root.path("infocode")) + ")");
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return "";
            }
            return text(node.path(0));
        }
        return Objects.toString(node.asText(""), "");
    }

    private record PendingAuthorization(String userId, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private record ReverseGeocodeResult(String address,
                                        String poiName,
                                        String adcode,
                                        String city,
                                        String province) {
    }

    private record PlaceSearchResult(double longitude,
                                     double latitude,
                                     String poiName,
                                     String address,
                                     String adcode,
                                     String city,
                                     String province) {
    }

    public record AuthorizedLocation(String userId,
                                     double longitude,
                                     double latitude,
                                     Double accuracyMeters,
                                     String address,
                                     String poiName,
                                     String adcode,
                                     String city,
                                     String province,
                                     Instant authorizedAt) {
        public boolean isExpired(Duration ttl) {
            Duration effectiveTtl = ttl == null ? DEFAULT_LOCATION_TTL : ttl;
            return Instant.now().isAfter(authorizedAt.plus(effectiveTtl));
        }

        public String bestDisplayAddress() {
            if (poiName != null && !poiName.isBlank() && address != null && !address.isBlank()) {
                return poiName + " - " + address;
            }
            if (address != null && !address.isBlank()) {
                return address;
            }
            if (poiName != null && !poiName.isBlank()) {
                return poiName;
            }
            return longitude + "," + latitude;
        }
    }
}
