package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class IpInfoTool implements ToolService.ProjectTool {

    private static final String API_URL = "https://uapis.cn/api/v1/network/ipinfo";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public IpInfoTool() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), new ObjectMapper());
    }

    IpInfoTool(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "lookup_ip_info", description = "Query location, ISP, ASN, and IP range for a public IPv4, IPv6 address, or domain.")
    public String lookupIpInfo(String ip) {
        return query(ip, false);
    }

    @Tool(name = "lookup_commercial_ip_info", description = "Query more detailed commercial IP geolocation data for a public IPv4, IPv6 address, or domain.")
    public String lookupCommercialIpInfo(String ip) {
        return query(ip, true);
    }

    private String query(String ip, boolean commercial) {
        if (ip == null || ip.isBlank()) {
            return "请提供要查询的公网 IP 地址或域名。";
        }

        try {
            URI uri = buildUri(ip.trim(), commercial);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(commercial ? 12 : 8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = parseBody(response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return formatSuccess(body);
            }
            return formatError(response.statusCode(), body);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "IP 查询被中断，请稍后再试。";
        } catch (Exception e) {
            return "IP 查询失败：" + e.getMessage();
        }
    }

    private static URI buildUri(String ip, boolean commercial) {
        StringBuilder url = new StringBuilder(API_URL)
                .append("?ip=")
                .append(URLEncoder.encode(ip, StandardCharsets.UTF_8));
        if (commercial) {
            url.append("&source=commercial");
        }
        return URI.create(url.toString());
    }

    private Map<String, Object> parseBody(String responseBody) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(responseBody, MAP_TYPE);
    }

    private static String formatSuccess(Map<String, Object> body) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "IP", body.get("ip"));
        addLine(lines, "位置", body.get("region"));
        addLine(lines, "运营商", body.get("isp"));
        addLine(lines, "归属机构", body.get("llc"));
        addLine(lines, "ASN", body.get("asn"));
        addLine(lines, "纬度", body.get("latitude"));
        addLine(lines, "经度", body.get("longitude"));
        addLine(lines, "IP 段起始", body.get("beginip"));
        addLine(lines, "IP 段结束", body.get("endip"));

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            if (!isKnownField(key)) {
                addLine(lines, key, entry.getValue());
            }
        }

        return lines.isEmpty() ? "查询成功，但接口未返回可展示的信息。" : String.join("\n", lines);
    }

    private static String formatError(int statusCode, Map<String, Object> body) {
        Object message = body.get("message");
        if (message != null && !message.toString().isBlank()) {
            return "IP 查询失败（HTTP " + statusCode + "）：" + message;
        }
        Object code = body.get("code");
        if (code != null && !code.toString().isBlank()) {
            return "IP 查询失败（HTTP " + statusCode + "）：" + code;
        }
        return "IP 查询失败，HTTP 状态码：" + statusCode;
    }

    private static void addLine(List<String> lines, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            lines.add(label + "：" + value);
        }
    }

    private static boolean isKnownField(String key) {
        return switch (key) {
            case "ip", "region", "isp", "llc", "asn", "latitude", "longitude", "beginip", "endip" -> true;
            default -> false;
        };
    }
}
