package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DiDi taxi-hailing tools exposed as Spring AI {@link Tool} methods.
 *
 * <p>Session state machine: {@code taxi_create_order} requires a prior
 * {@code taxi_estimate} call. The traceId from estimate is stored server-side
 * (keyed by userId) and NEVER exposed to the LLM. create_order looks up the
 * latest valid session internally — the LLM cannot fabricate or reuse traceIds.</p>
 *
 * <p><b>Same-request gate:</b> A ThreadLocal flag prevents the LLM from chaining
 * {@code estimate → create_order} inside a single tool-calling loop. The flag is
 * armed inside {@code taxiEstimate()} and checked (rejected) inside
 * {@code taxiCreateOrder()}. It is reset by {@link #clearCurrentUser()} at the
 * end of each request, so a user confirmation in the next message passes through.</p>
 *
 * <p>This design enforces the DiDi spec requirement that the user must explicitly
 * confirm their car-type choice before an order is created. The LLM sees estimate
 * results but not the traceId, so it cannot chain estimate → create_order in a
 * single tool loop.</p>
 *
 * <p>Coordinate parameters must be strings (per DiDi API requirement).</p>
 */
public class DiDiTaxiTools implements ToolService.ProjectTool {

    @Override
    public String category() {
        return "didi_taxi";
    }

    private static final Logger log = LoggerFactory.getLogger(DiDiTaxiTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Session TTL: estimate → create_order must happen within this window. */
    static final long ESTIMATE_SESSION_TTL_MINUTES = 5;

    // -------------------------------------------------------------------------
    // ThreadLocal user tracking: set by the request orchestrator before tool execution.
    // -------------------------------------------------------------------------

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    /**
     * Same-request gate: prevents the LLM from calling estimate → create_order
     * in a single tool-calling loop without waiting for user confirmation.
     * Set to true inside estimate, checked (and rejected) inside create_order.
     * Reset by clearCurrentUser() at the end of each request.
     */
    private static final ThreadLocal<Boolean> ESTIMATE_CALLED_THIS_REQUEST = new ThreadLocal<>();

    /** Set before the orchestration loop starts executing tools. */
    public static void setCurrentUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT_USER.set(userId);
        }
    }

    /** Clear in the caller's finally block after tool execution. */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
        ESTIMATE_CALLED_THIS_REQUEST.remove();
    }

    private String currentUserId() {
        return CURRENT_USER.get();
    }

    // -------------------------------------------------------------------------
    // Session state machine
    // -------------------------------------------------------------------------

    private record EstimateItem(String productName, String productCategory, String priceText) {}

    private record EstimateSession(String traceId, String fromName, String toName, Instant estimatedAt,
                                   List<EstimateItem> items) {
        boolean isExpired() {
            return Instant.now().isAfter(estimatedAt.plus(ESTIMATE_SESSION_TTL_MINUTES, ChronoUnit.MINUTES));
        }
    }

    private final DiDiMcpClient client;
    private final ConcurrentHashMap<String, EstimateSession> estimateSessions = new ConcurrentHashMap<>();

    public DiDiTaxiTools(DiDiMcpClient client) {
        this.client = client;
    }

    /** Register a traceId after a successful estimate, keyed by userId. */
    private void registerSession(String userId, String traceId, String fromName, String toName,
                                 List<EstimateItem> items) {
        estimateSessions.put(userId, new EstimateSession(traceId, fromName, toName, Instant.now(), items));
        evictExpiredSessions();
        log.info("Estimate session registered: userId={}, traceId={}, from={}, to={}, total sessions={}",
                userId, traceId, fromName, toName, estimateSessions.size());
    }

    /**
     * Look up and consume the latest valid session for a user.
     * Returns null if no session exists or it has expired.
     */
    private EstimateSession consumeSession(String userId) {
        EstimateSession session = estimateSessions.get(userId);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            estimateSessions.remove(userId);
            return null;
        }
        estimateSessions.remove(userId);
        log.info("Estimate session consumed: userId={}, traceId={}", userId, session.traceId());
        return session;
    }

    private void evictExpiredSessions() {
        estimateSessions.values().removeIf(EstimateSession::isExpired);
    }

    // -------------------------------------------------------------------------
    // Taxi tools
    // -------------------------------------------------------------------------

    @Tool(name = "didi_taxi_estimate",
          description = "滴滴打车价格预估。查询从起点到终点的可用车型和预估价格。"
                  + "调用前需先通过 query_amap_place_ids 获取起终点的坐标(lng,lat)。"
                  + "返回各车型名称、价格，但不包含 traceId（traceId 由系统内部保存）。"
                  + "调用后必须向用户展示车型和价格并等待用户选择，严禁在同一轮对话中继续调用 didi_taxi_create_order。")
    public String taxiEstimate(
            @ToolParam(description = "出发地经度（字符串格式），来自 query_amap_place_ids 返回的 location 中的经度") String fromLng,
            @ToolParam(description = "出发地纬度（字符串格式），来自 query_amap_place_ids 返回的 location 中的纬度") String fromLat,
            @ToolParam(description = "出发地名称，如'北京西站'") String fromName,
            @ToolParam(description = "目的地经度（字符串格式），来自 query_amap_place_ids 返回的 location 中的经度") String toLng,
            @ToolParam(description = "目的地纬度（字符串格式），来自 query_amap_place_ids 返回的 location 中的纬度") String toLat,
            @ToolParam(description = "目的地名称，如'国贸'") String toName) {
        if (isAnyBlank(fromLng, fromLat, fromName, toLng, toLat, toName)) {
            return "价格预估失败：起终点坐标和名称不能为空。";
        }
        String userId = currentUserId();
        if (userId == null) {
            return "价格预估失败：无法确定当前用户身份，请重试。";
        }

        try {
            log.info("[DiDi Tool] taxiEstimate: from=({},{}){}, to=({},{}){}",
                    fromLng, fromLat, fromName, toLng, toLat, toName);

            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("from_lng", fromLng.trim());
            args.put("from_lat", fromLat.trim());
            args.put("from_name", fromName.trim());
            args.put("to_lng", toLng.trim());
            args.put("to_lat", toLat.trim());
            args.put("to_name", toName.trim());

            JsonNode result = client.callTool("taxi_estimate", args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            String traceId = structured.path("traceId").asText(null);
            if (traceId == null || traceId.isBlank()) {
                return "价格预估失败：DiDi 服务未返回有效的 traceId。";
            }

            // Parse items for session storage and user display
            JsonNode itemsNode = structured.path("items");
            List<EstimateItem> items = new ArrayList<>();
            if (itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    String productName = item.path("productName").asText("未知");
                    String productCategory = item.path("productCategory").asText("");
                    String priceText = item.path("priceText").asText("");
                    items.add(new EstimateItem(productName, productCategory, priceText));
                }
            }

            // Register session server-side — traceId NEVER exposed to LLM
            registerSession(userId, traceId, fromName, toName, items);

            // Arm the same-request gate: prevent create_order in this tool loop
            ESTIMATE_CALLED_THIS_REQUEST.set(true);

            // Build user-facing output (no traceId!)
            StringBuilder sb = new StringBuilder();
            sb.append("【滴滴打车】价格预估\n");
            sb.append("路线：").append(fromName).append(" → ").append(toName).append("\n\n");
            sb.append(text);

            // Add a clear choice prompt
            if (!items.isEmpty()) {
                sb.append("\n\n---\n");
                sb.append("请选择您想要的车型，告诉我即可下单（如：\"选特惠快车\"）。");
            }

            return sb.toString().trim();
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_estimate failed: from={}, to={}, error={}", fromName, toName, e.getMessage());
            return "价格预估失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_estimate unexpected error: from={}, to={}", fromName, toName, e);
            return "价格预估异常：" + e.getMessage();
        }
    }

    @Tool(name = "didi_taxi_create_order",
          description = "创建滴滴打车订单。调用后立即产生真实订单（测试环境为模拟订单）。"
                  + "【重要】调用前必须向用户展示车型和价格并获得明确确认。"
                  + "product_category 来自 didi_taxi_estimate 返回结果中的车型标识。"
                  + "【底层保障】必须事先调用 didi_taxi_estimate，否则会被服务端直接拒绝。"
                  + "预估有效期为5分钟，超时需重新预估。")
    public String taxiCreateOrder(
            @ToolParam(description = "车型标识，来自 didi_taxi_estimate 返回的 productCategory。如选择多种车型用英文逗号分隔") String productCategory) {
        if (productCategory == null || productCategory.isBlank()) {
            return "创建订单失败：请提供车型标识（productCategory），如\"201\"表示特惠快车。";
        }
        String userId = currentUserId();
        if (userId == null) {
            return "创建订单失败：无法确定当前用户身份，请重试。";
        }

        // --- same-request gate: reject if estimate was just called in this tool loop ---
        if (Boolean.TRUE.equals(ESTIMATE_CALLED_THIS_REQUEST.get())) {
            log.warn("didi taxi_create_order rejected by same-request gate: userId={}, productCategory={}",
                    userId, productCategory);
            return "下单被拒绝：请等待用户确认车型后再调用 didi_taxi_create_order。"
                    + "didi_taxi_estimate 和 didi_taxi_create_order 不能在同一轮对话中连续调用，"
                    + "必须先向用户展示车型和价格，等用户明确选择后再下单。";
        }
        // --- end same-request gate ---

        // --- session gate: must have a valid prior estimate for this user ---
        EstimateSession session = consumeSession(userId);
        if (session == null) {
            log.warn("didi taxi_create_order rejected: userId={} has no valid estimate session", userId);
            return "创建订单被拒绝：请先调用 didi_taxi_estimate 获取最新价格预估，"
                    + "预估有效期为" + ESTIMATE_SESSION_TTL_MINUTES + "分钟。";
        }

        // Validate productCategory against estimate items (belt-and-suspenders)
        String normalizedCategory = productCategory.trim();
        boolean matched = session.items().stream()
                .anyMatch(item -> normalizedCategory.equals(item.productCategory()));
        if (!matched) {
            log.warn("didi taxi_create_order: productCategory={} not found in estimate items, userId={}",
                    normalizedCategory, userId);
            return "创建订单失败：车型标识 \"" + normalizedCategory + "\" 不在预估结果中。"
                    + "请从预估返回的车型中选择，或重新调用 didi_taxi_estimate。";
        }
        // --- end session gate ---

        try {
            log.info("[DiDi Tool] taxiCreateOrder: userId={}, productCategory={}, traceId={}",
                    userId, normalizedCategory, session.traceId());

            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("product_category", normalizedCategory);
            args.put("estimate_trace_id", session.traceId());

            JsonNode result = client.callTool("taxi_create_order", args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            StringBuilder sb = new StringBuilder();
            sb.append("【滴滴打车】订单已创建\n");
            sb.append("路线：").append(session.fromName()).append(" → ").append(session.toName()).append("\n\n");
            sb.append(text);

            String orderId = structured.path("orderId").asText(null);
            if (orderId != null && !orderId.isBlank()) {
                sb.append("\n\n---\n");
                sb.append("【订单ID - 用于后续查询】\n");
                sb.append("orderId: ").append(orderId).append("\n");
            }

            return sb.toString().trim();
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_create_order failed: category={}, userId={}, error={}",
                    productCategory, userId, e.getMessage());
            if (e.isExpired()) {
                return "创建订单失败：预估结果已过期，请重新调用 didi_taxi_estimate 获取最新价格和 traceId。";
            }
            return "创建订单失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_create_order unexpected error", e);
            return "创建订单异常：" + e.getMessage();
        }
    }

    @Tool(name = "didi_taxi_query_order",
          description = "查询滴滴打车订单状态和司机信息。不传 orderId 则查询当前未完成订单。"
                  + "可查询订单状态、司机姓名、车型、车牌、联系电话、距离和预计到达时间。"
                  + "建议轮询间隔：匹配中30秒，已接单30秒，司机已到达60秒，行程中60秒。")
    public String taxiQueryOrder(
            @ToolParam(description = "订单ID，来自 didi_taxi_create_order 返回的 orderId。留空则查询当前未完成订单") String orderId) {
        try {
            log.info("[DiDi Tool] taxiQueryOrder: orderId={}", orderId);
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            if (orderId != null && !orderId.isBlank()) {
                args.put("order_id", orderId.trim());
            }

            JsonNode result = client.callTool("taxi_query_order", args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            StringBuilder sb = new StringBuilder();
            sb.append("【滴滴打车】订单状态\n\n");
            sb.append(text);

            String currentOrderId = structured.path("orderId").asText(null);
            if (currentOrderId != null && !currentOrderId.isBlank()) {
                sb.append("\n\n订单ID: ").append(currentOrderId);
            }

            return sb.toString().trim();
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_query_order failed: orderId={}, error={}", orderId, e.getMessage());
            return "查询订单失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_query_order unexpected error", e);
            return "查询订单异常：" + e.getMessage();
        }
    }

    @Tool(name = "didi_taxi_cancel_order",
          description = "取消滴滴打车订单。行程中或已完单的订单不支持取消。")
    public String taxiCancelOrder(
            @ToolParam(description = "要取消的订单ID") String orderId,
            @ToolParam(description = "取消原因（可选），如'行程有变'") String reason) {
        if (orderId == null || orderId.isBlank()) {
            return "取消订单失败：请提供订单ID。";
        }

        try {
            log.info("[DiDi Tool] taxiCancelOrder: orderId={}, reason={}", orderId, reason);
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("order_id", orderId.trim());
            if (reason != null && !reason.isBlank()) {
                args.put("reason", reason.trim());
            }

            JsonNode result = client.callTool("taxi_cancel_order", args);
            String text = client.getTextContent(result);

            return "【滴滴打车】取消订单\n\n" + text;
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_cancel_order failed: orderId={}, error={}", orderId, e.getMessage());
            return "取消订单失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_cancel_order unexpected error", e);
            return "取消订单异常：" + e.getMessage();
        }
    }

    @Tool(name = "didi_taxi_get_driver_location",
          description = "获取滴滴司机实时位置坐标。仅在有进行中订单时有效。"
                  + "返回司机当前经纬度，可用于后续转换为可读地址。")
    public String taxiGetDriverLocation(
            @ToolParam(description = "订单ID") String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "获取司机位置失败：请提供订单ID。";
        }

        try {
            log.info("[DiDi Tool] taxiGetDriverLocation: orderId={}", orderId);
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("order_id", orderId.trim());

            JsonNode result = client.callTool("taxi_get_driver_location", args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            StringBuilder sb = new StringBuilder();
            sb.append("【滴滴打车】司机位置\n\n");
            sb.append(text);

            double lng = structured.path("longitude").asDouble(Double.NaN);
            double lat = structured.path("latitude").asDouble(Double.NaN);
            if (!Double.isNaN(lng) && !Double.isNaN(lat)) {
                sb.append("\n\n司机坐标: ").append(lng).append(",").append(lat);
            }

            return sb.toString().trim();
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_get_driver_location failed: orderId={}, error={}", orderId, e.getMessage());
            return "获取司机位置失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_get_driver_location unexpected error", e);
            return "获取司机位置异常：" + e.getMessage();
        }
    }

    @Tool(name = "didi_taxi_generate_ride_app_link",
          description = "生成跳转滴滴出行 App 的打车深度链接，用户在 App 内完成下单。"
                  + "适用于不需要 API 托管下单、让用户自己在 App 里下单的场景。"
                  + "需要滴滴出行 App 7.1.2 及以上版本。"
                  + "调用前需先通过 query_amap_place_ids 获取起终点坐标。"
                  + "与 didi_taxi_create_order 是二选一的关系，不要同时调用。")
    public String taxiGenerateRideAppLink(
            @ToolParam(description = "出发地经度（字符串格式），来自 query_amap_place_ids") String fromLng,
            @ToolParam(description = "出发地纬度（字符串格式），来自 query_amap_place_ids") String fromLat,
            @ToolParam(description = "目的地经度（字符串格式），来自 query_amap_place_ids") String toLng,
            @ToolParam(description = "目的地纬度（字符串格式），来自 query_amap_place_ids") String toLat,
            @ToolParam(description = "车型品类标识（可选），来自 didi_taxi_estimate 的 productCategory") String productCategory) {
        if (isAnyBlank(fromLng, fromLat, toLng, toLat)) {
            return "生成行程链接失败：起终点坐标不能为空。";
        }

        try {
            log.info("[DiDi Tool] taxiGenerateRideAppLink: from=({},{}), to=({},{}), category={}",
                    fromLng, fromLat, toLng, toLat, productCategory);
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("from_lng", fromLng.trim());
            args.put("from_lat", fromLat.trim());
            args.put("to_lng", toLng.trim());
            args.put("to_lat", toLat.trim());
            if (productCategory != null && !productCategory.isBlank()) {
                args.put("product_category", productCategory.trim());
            }

            JsonNode result = client.callTool("taxi_generate_ride_app_link", args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            log.info("didi taxi_generate_ride_app_link success: textLen={}, hasStructured={}",
                    text != null ? text.length() : 0, !structured.isMissingNode());

            StringBuilder sb = new StringBuilder();
            sb.append("【滴滴打车】行程链接\n\n");
            sb.append(text);

            String appLink = structured.path("appLink").asText(null);
            if (appLink == null || appLink.isBlank()) {
                appLink = structured.path("link").asText(null);
            }
            if (appLink == null || appLink.isBlank()) {
                appLink = structured.path("url").asText(null);
            }
            if (appLink != null && !appLink.isBlank()) {
                sb.append("\n\n应用链接: ").append(appLink);
            }

            return sb.toString().trim();
        } catch (DiDiMcpClient.DiDiMcpException e) {
            log.warn("didi taxi_generate_ride_app_link failed: error={}", e.getMessage());
            return "生成行程链接失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("didi taxi_generate_ride_app_link unexpected error", e);
            return "生成行程链接异常：" + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isAnyBlank(String... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
