package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MeituanShoppingTool implements ToolService.ProjectTool {

    public static final String TOKEN_PROPERTY = "meituan.api.token";
    public static final String TOKEN_ENV = "MEITUAN_API_TOKEN";

    private static final String BASE_URL_PROPERTY = "meituan.api.base-url";
    private static final String SEARCH_PATH_PROPERTY = "meituan.api.product-search-path";
    private static final String PAYMENT_PATH_PROPERTY = "meituan.api.payment-link-path";
    private static final String DEFAULT_SEARCH_PATH = "/products/search";
    private static final String DEFAULT_PAYMENT_PATH = "/payment/link";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Environment environment;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MeituanShoppingTool(Environment environment) {
        this(environment, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build(), new ObjectMapper());
    }

    MeituanShoppingTool(Environment environment, HttpClient httpClient, ObjectMapper objectMapper) {
        this.environment = environment;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "search_meituan_products", description = "Search Meituan official merchant products by keyword and location, returning several candidates for the user to choose.")
    public String searchProducts(String keyword, String location, Integer maxResults) {
        if (keyword == null || keyword.isBlank()) {
            return "请提供要搜索的商品关键词。";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", keyword.trim());
        putIfPresent(body, "location", location);
        body.put("maxResults", normalizeMaxResults(maxResults));

        return postConfiguredEndpoint(SEARCH_PATH_PROPERTY, DEFAULT_SEARCH_PATH, body,
                "美团商品搜索接口未配置，无法查询商品。",
                "商品搜索失败");
    }

    @Tool(name = "create_meituan_payment_link", description = "Create an official Meituan or merchant payment link after the user selected a product and provided a delivery address.")
    public String createPaymentLink(String productId, String productName, String address,
                                    String contactName, String phone, Integer quantity) {
        if (productId == null || productId.isBlank()) {
            return "请先让用户选择一个商品，再生成付款链接。";
        }
        if (address == null || address.isBlank()) {
            return "生成付款链接前需要先向用户索要收货地址。";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId.trim());
        putIfPresent(body, "productName", productName);
        body.put("address", address.trim());
        putIfPresent(body, "contactName", contactName);
        putIfPresent(body, "phone", phone);
        body.put("quantity", quantity == null || quantity < 1 ? 1 : quantity);

        return postConfiguredEndpoint(PAYMENT_PATH_PROPERTY, DEFAULT_PAYMENT_PATH, body,
                "美团官方付款链接接口未配置，无法生成付款链接。",
                "付款链接生成失败");
    }

    private String postConfiguredEndpoint(String pathProperty, String defaultPath,
                                          Map<String, Object> body, String missingEndpointMessage,
                                          String failurePrefix) {
        String token = resolveToken();
        if (token == null || token.isBlank()) {
            return "未配置美团 Token。请在配置中添加 " + TOKEN_PROPERTY + "=你的美团Token"
                    + "，或设置环境变量 " + TOKEN_ENV + "。";
        }

        String baseUrl = property(BASE_URL_PROPERTY);
        if (baseUrl == null || baseUrl.isBlank()) {
            return missingEndpointMessage + " 请同时确认美团官方业务线的 API 地址。Token 变量名是 " + TOKEN_PROPERTY + "。";
        }

        try {
            String requestJson = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(resolveUri(baseUrl, property(pathProperty), defaultPath))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Meituan-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return formatResponse(response.statusCode(), response.body(), failurePrefix);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failurePrefix + "：请求被中断，请稍后再试。";
        } catch (Exception e) {
            return failurePrefix + "：" + e.getMessage();
        }
    }

    private String resolveToken() {
        String token = property(TOKEN_PROPERTY);
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String envToken = System.getenv(TOKEN_ENV);
        return envToken == null ? null : envToken.trim();
    }

    private String property(String name) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static URI resolveUri(String baseUrl, String configuredPath, String defaultPath) {
        String path = configuredPath == null || configuredPath.isBlank() ? defaultPath : configuredPath.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return URI.create(path);
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }

    private String formatResponse(int statusCode, String responseBody, String failurePrefix) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            return statusCode >= 200 && statusCode < 300
                    ? "请求成功，但美团接口未返回内容。"
                    : failurePrefix + "：HTTP " + statusCode;
        }

        Map<String, Object> body = parseJsonObject(responseBody);
        if (statusCode >= 200 && statusCode < 300) {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        }

        Object message = body.get("message");
        if (message == null) {
            message = body.get("msg");
        }
        return failurePrefix + "：HTTP " + statusCode
                + (message == null ? "" : "，" + message);
    }

    private Map<String, Object> parseJsonObject(String responseBody) throws JsonProcessingException {
        return objectMapper.readValue(responseBody, MAP_TYPE);
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value.trim());
        }
    }

    private static int normalizeMaxResults(Integer maxResults) {
        if (maxResults == null) {
            return 5;
        }
        return Math.max(1, Math.min(maxResults, 10));
    }
}
