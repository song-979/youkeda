package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentCapability;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentTask;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentUnit;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MeituanPaotuiAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(MeituanPaotuiAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final long PENDING_ORDER_TTL_MILLIS = 15 * 60_000L;

    private final MeituanPaotuiTool tool;
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    public MeituanPaotuiAgent(MeituanPaotuiTool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return "MEITUAN_PAOTUI";
    }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability(
                "meituan-paotui",
                "Handles Meituan Paotui helper flows with deterministic login precheck before order collection.",
                List.of("meituan-paotui", "delivery", "errand", "purchase-agent"),
                "text"
        );
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String userId = task.parameters().get("userId") instanceof String value ? value : "anonymous";
        tool.setPassportUserScope(userId);
        try {
            return executeForUser(task, userId);
        } finally {
            tool.clearPassportUserScope();
        }
    }

    private AgentResult executeForUser(AgentTask task, String userId) {
        String request = task.instruction() == null ? "" : task.instruction().trim();
        log.info("MeituanPaotuiAgent executing request: {}", request);

        if (isAuthorizationStart(request)) {
            String authorizationReply = tool.startPassportAuthorization(true);
            return AgentResult.success(task.taskId(), authorizationReply, authorizationReply);
        }

        if (isAuthorizationConfirmation(request)) {
            String authorizationResult = tool.completePassportAuthorization();
            if (!authorizationResult.contains("授权成功")) {
                return AgentResult.success(task.taskId(), authorizationResult, authorizationResult);
            }
        }

        String loginResult = tool.checkLogin();
        log.info("MeituanPaotuiAgent login check result: {}", abbreviate(loginResult));
        if (!looksLoggedIn(loginResult)) {
            if (needsPassportAuthorization(loginResult)) {
                String authorizationReply = tool.startPassportAuthorization();
                return AgentResult.success(task.taskId(), authorizationReply, authorizationReply);
            }
            return AgentResult.success(task.taskId(), loginFailureReply(loginResult), loginResult);
        }

        if (isOrderConfirmation(request)) {
            String submissionReply = submitPendingOrder(userId, request);
            return AgentResult.success(task.taskId(), submissionReply, submissionReply);
        }

        if (isAuthorizationQuestion(request)) {
            String reply = "当前美团账号已完成授权，可以继续跑腿下单。";
            return AgentResult.success(task.taskId(), reply, reply);
        }

        String conversation = mergeConversation(task, request);
        String reply = buildNextStepReply(conversation, request, userId);
        return AgentResult.success(task.taskId(), reply, reply);
    }

    private static boolean looksLoggedIn(String loginResult) {
        if (loginResult == null || loginResult.isBlank()) {
            return false;
        }
        return loginResult.contains("已登录")
                || loginResult.toLowerCase().contains("logged in")
                || loginResult.toLowerCase().contains("success");
    }

    private static String loginFailureReply(String loginResult) {
        String text = loginResult == null ? "" : loginResult;
        if (text.contains("找不到") || text.contains("未配置") || text.contains("zip")) {
            return "美团跑腿运行包配置异常，请检查本地配置后重启服务。";
        }
        return "美团跑腿登录状态异常，请先完成授权登录后再继续。";
    }

    private static boolean needsPassportAuthorization(String loginResult) {
        if (loginResult == null || loginResult.isBlank()) {
            return true;
        }
        return loginResult.contains("Passport 授权") || loginResult.contains("未登录")
                || loginResult.contains("AUTH_FAILED") || loginResult.contains("401");
    }

    private static boolean isAuthorizationConfirmation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim();
        return normalized.contains("已授权") || normalized.contains("授权完成")
                || normalized.contains("已经授权") || normalized.contains("完成授权");
    }

    private static boolean isAuthorizationStart(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase();
        return normalized.contains("开始授权") || normalized.contains("发起授权")
                || normalized.contains("重新授权") || normalized.contains("generate_authorization_url");
    }

    private static boolean isAuthorizationQuestion(String text) {
        return text != null && text.contains("授权")
                && (text.contains("需要") || text.contains("是否") || text.contains("要不要"));
    }

    private String buildNextStepReply(String conversation, String currentRequest, String userId) {
        List<String> missing = new ArrayList<>();

        if (!hasSpecificItem(conversation)) {
            missing.add("具体要买的瑞幸饮品和规格");
        }
        if (!hasRecipientAddress(conversation)) {
            missing.add("收货地址");
        }
        if (!hasPhone(conversation)) {
            missing.add("联系电话");
        }
        if (!hasPurchasePreference(conversation)) {
            missing.add("购买门店，或确认就近购买");
        }

        if (missing.isEmpty() && isPreviewConfirmation(currentRequest)) {
            return buildPoiLookupReply(conversation, currentRequest, userId);
        }

        if (missing.isEmpty()) {
            return buildPoiLookupReply(conversation, currentRequest, userId);
        }

        StringBuilder reply = new StringBuilder();
        reply.append("美团跑腿已登录。\n\n");
        reply.append("下单前还缺少以下信息：\n\n");
        for (String item : missing) {
            reply.append(item).append("\n\n");
        }
        reply.append("请补充后我再继续生成费用预览。");
        return reply.toString().trim();
    }

    private String buildPoiLookupReply(String conversation, String currentRequest, String userId) {
        String city = inferCity(conversation);
        String recipientKeyword = extractRecipientKeyword(conversation);
        String shopKeyword = extractShopKeyword(conversation);

        String recipientResult = tool.searchPoi(recipientKeyword, city, null, null);
        log.info("MeituanPaotuiAgent recipient POI result: {}", abbreviate(recipientResult));
        if (isAuthError(recipientResult)) {
            return """
                    美团跑腿已登录，但业务接口鉴权失败。

                    当前 Token 可以通过登录检查，但地址查询接口返回未授权，暂时不能继续生成费用预览。

                    请重新获取美团跑腿 Skill 对应的 Passport 授权 Token，或确认该 Token 是否已开通跑腿业务接口权限。
                    """;
        }
        if (isToolError(recipientResult)) {
            return """
                    美团跑腿已登录，但收货地址查询失败。

                    请重新授权美团跑腿后再试，或把收货地址补充得更精确一些。

                    在查到有效地址并生成费用预览前，我不会提交订单。
                    """;
        }
        List<Poi> recipientPois = parsePois(recipientResult);
        if (recipientPois.isEmpty()) {
            return "没有找到可用的收货地址，请把地址补充到园区、楼栋或门牌号。";
        }
        if (!isDetailedAddress(recipientKeyword)) {
            return "收货地址还不够明确，请选择：\n\n" + formatPois(recipientPois, 3);
        }
        Poi recipient = recipientPois.getFirst();

        String shopResult = tool.searchPoi(shopKeyword, city, null, null);
        log.info("MeituanPaotuiAgent shop POI result: {}", abbreviate(shopResult));
        if (isAuthError(shopResult)) {
            return """
                    美团跑腿已登录，但业务接口鉴权失败。

                    当前 Token 可以通过登录检查，但购买门店查询接口返回未授权，暂时不能继续生成费用预览。

                    请重新获取美团跑腿 Skill 对应的 Passport 授权 Token，或确认该 Token 是否已开通跑腿业务接口权限。
                    """;
        }
        if (isToolError(shopResult)) {
            return """
                    美团跑腿已登录，但购买门店查询失败。

                    请确认门店名称是否准确，或回复“就近购买”。

                    在查到购买地址并生成费用预览前，我不会提交订单。
                    """;
        }
        List<Poi> shopPois = parsePois(shopResult);
        if (shopPois.isEmpty()) {
            return "收货地址已确认，但没有找到可用的购买门店。请换一个门店名称或回复“就近购买”。";
        }

        Optional<Poi> matchedShop = selectPoi(shopPois, shopKeyword, extractSelection(currentRequest));
        if (matchedShop.isEmpty()) {
            return """
                    收货地址已自动确认：%s

                    没有精确找到“%s”，请选择购买门店：

                    %s

                    回复门店序号即可。不会再次要求确认收货地址。
                    """.formatted(displayRecipient(recipientKeyword, recipient), shopKeyword,
                    formatPois(shopPois, 3)).trim();
        }

        return previewPurchase(conversation, recipientKeyword, recipient, matchedShop.get(), userId);
    }

    private String previewPurchase(String conversation, String recipientKeyword, Poi recipient, Poi shop,
                                   String userId) {
        String phone = extractPhone(conversation);
        String item = extractPurchaseDetail(conversation);
        int cityId = cityId(inferCity(conversation));

        String senderJson = addressJson(shop.name() + " " + shop.address(), shop, phone, cityId);
        String recipientJson = addressJson(displayRecipient(recipientKeyword, recipient), recipient, phone, cityId);
        String goodsJson;
        try {
            goodsJson = JSON.writeValueAsString(Map.of(
                    "goodsName", item,
                    "goodsWeight", 1,
                    "goodTypes", List.of(2),
                    "goodTypeNames", List.of("餐饮")
            ));
        } catch (Exception e) {
            return "订单参数生成失败，请稍后重试。";
        }

        String preview = tool.previewOrder(senderJson, recipientJson, goodsJson,
                "2", "0", "0", "0", item, "", null);
        log.info("MeituanPaotuiAgent preview result: {}", abbreviate(preview));
        if (isToolError(preview)) {
            return "地址和门店已确认，但费用预览生成失败，请稍后重试。订单尚未提交。";
        }
        pendingOrders.put(userId, new PendingOrder(
                senderJson, recipientJson, goodsJson,
                "2", "0", "0", "0", item, "", null,
                shop.name(), displayRecipient(recipientKeyword, recipient), phone, item,
                System.currentTimeMillis()
        ));
        return formatPreview(preview, shop.name(), displayRecipient(recipientKeyword, recipient), phone, item);
    }

    private String submitPendingOrder(String userId, String confirmation) {
        PendingOrder order = pendingOrders.get(userId);
        if (order == null || System.currentTimeMillis() - order.createdAt() > PENDING_ORDER_TTL_MILLIS) {
            pendingOrders.remove(userId);
            return "费用预览已失效，请重新提供订单信息生成新的预览。订单尚未提交。";
        }

        String result = tool.submitOrder(
                order.senderJson(), order.recipientJson(), order.goodsJson(),
                order.businessType(), order.bizTypeSceneTag(), order.businessTypeTag(),
                order.tipFee(), order.purchaseDetail(), order.remark(), order.conversationId(),
                confirmation
        );
        log.info("MeituanPaotuiAgent submit result: {}", abbreviate(result));
        if (isToolError(result)) {
            if (result.contains("\"code\":10001") || result.contains("请输入商品信息")) {
                pendingOrders.remove(userId);
                return "商品信息格式未通过校验，请重新生成费用预览后再确认。订单尚未提交。";
            }
            return "订单提交失败，请稍后重试。不会重复提交。";
        }
        pendingOrders.remove(userId);
        return formatSubmission(result);
    }

    private static String formatSubmission(String result) {
        String orderId = "";
        try {
            JsonNode root = JSON.readTree(result);
            for (String field : List.of("orderId", "order_id", "wmOrderId")) {
                if (root.hasNonNull(field) && !root.path(field).asText().isBlank()) {
                    orderId = root.path(field).asText();
                    break;
                }
            }
        } catch (Exception ignored) {
            // A successful non-JSON response still means the order was accepted.
        }
        String orderLine = orderId.isBlank() ? "" : "\n\n订单号 " + orderId;
        return "订单已提交！" + orderLine
                + "\n\n请在 15 分钟内打开美团 App，在“我的订单”中完成支付。支付成功后骑手才会接单。";
    }

    private static String formatPreview(String preview, String shop, String recipient,
                                        String phone, String item) {
        try {
            JsonNode root = JSON.readTree(preview);
            String fee = root.path("💰 配送费").asText();
            String distance = root.path("📍 距离").asText();
            String eta = root.path("🕐 预计时效").asText();
            return """
                    🛵 服务类型 帮买

                    🏪 购买地址 %s

                    🏠 收件地址 %s · %s

                    🛍️ 物品 %s

                    📍 距离 %s

                    💰 配送费 %s

                    🕐 预计时效 %s

                    确认下单吗？
                    """.formatted(shop, recipient, maskPhone(phone), item,
                    blankToFallback(distance, "以实际配送为准"),
                    blankToFallback(fee, "以实际订单为准"),
                    blankToFallback(eta, "以实际订单为准")).trim();
        } catch (Exception e) {
            return "费用预览已生成。\n\n确认下单吗？";
        }
    }

    private static String maskPhone(String phone) {
        return phone != null && phone.matches("1[3-9]\\d{9}")
                ? phone.substring(0, 3) + "****" + phone.substring(7)
                : "";
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value;
    }

    private static boolean isOrderConfirmation(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        return "确认下单".equals(normalized) || "确认".equals(normalized)
                || "确定下单".equals(normalized) || "同意下单".equals(normalized);
    }

    private static String addressJson(String address, Poi poi, String phone, int cityId) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "address", address,
                    "houseNumber", "",
                    "lat", poi.lat(),
                    "lng", poi.lng(),
                    "name", "",
                    "phone", phone,
                    "cityId", cityId
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build address JSON", e);
        }
    }

    private static List<Poi> parsePois(String result) {
        List<Poi> pois = new ArrayList<>();
        try {
            JsonNode nodes = JSON.readTree(result).path("pois");
            if (!nodes.isArray()) {
                return List.of();
            }
            for (JsonNode node : nodes) {
                pois.add(new Poi(
                        node.path("name").asText(),
                        node.path("address").asText(),
                        node.path("lat").asLong(),
                        node.path("lng").asLong()
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return pois;
    }

    private static Optional<Poi> selectPoi(List<Poi> pois, String keyword, Integer selection) {
        if (selection != null && selection > 0 && selection <= Math.min(pois.size(), 3)) {
            return Optional.of(pois.get(selection - 1));
        }
        String expected = normalizePoiName(keyword);
        if (expected.length() < 3) {
            return Optional.empty();
        }
        return pois.stream()
                .filter(poi -> {
                    String candidate = normalizePoiName(poi.name());
                    return candidate.contains(expected) || expected.contains(candidate);
                })
                .findFirst();
    }

    private static String normalizePoiName(String value) {
        return value == null ? "" : value
                .replace("瑞幸咖啡", "")
                .replaceAll("[()（）·\\s]", "")
                .trim();
    }

    private static String formatPois(List<Poi> pois, int limit) {
        StringBuilder reply = new StringBuilder();
        for (int i = 0; i < Math.min(pois.size(), limit); i++) {
            Poi poi = pois.get(i);
            reply.append(i + 1).append(". ").append(poi.name())
                    .append("\n   ").append(poi.address()).append("\n\n");
        }
        return reply.toString().trim();
    }

    private static boolean isDetailedAddress(String keyword) {
        return keyword != null && keyword.length() >= 10
                && containsAny(keyword, "园区", "小区", "大厦", "写字楼", "栋", "楼", "号", "室");
    }

    private static String displayRecipient(String keyword, Poi poi) {
        String cleaned = keyword == null ? "" : keyword
                .replaceAll("(?s)(?:联系电话|电话|手机号).*$", "")
                .replaceAll("1[3-9]\\d{9}.*$", "")
                .trim();
        return cleaned.isBlank() ? poi.name() + " " + poi.address() : cleaned;
    }

    private static String extractPhone(String text) {
        Matcher matcher = PHONE_PATTERN.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }

    private static Integer extractSelection(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?:第|选|选择)?\\s*([1-3一二三])\\s*(?:个|号|家|店)?").matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        return switch (matcher.group(1)) {
            case "1", "一" -> 1;
            case "2", "二" -> 2;
            case "3", "三" -> 3;
            default -> null;
        };
    }

    private static String extractPurchaseDetail(String text) {
        if (text == null || text.isBlank()) {
            return "餐饮";
        }
        Matcher matcher = Pattern.compile("(?:店|就近购买)[，,：:\\s]*(.*?)(?:收货地址|送到|配送至|电话|$)")
                .matcher(text);
        if (matcher.find() && !matcher.group(1).isBlank()) {
            String detail = matcher.group(1)
                    .replaceFirst("^[)）】\\]，,：:；;、\\s]+", "")
                    .replaceAll("[；;，,]+$", "")
                    .trim();
            return detail.isBlank() ? "用户指定饮品" : detail;
        }
        return "用户指定饮品";
    }

    private static int cityId(String city) {
        return switch (city) {
            case "上海" -> 310100;
            case "广州" -> 440100;
            case "深圳" -> 440300;
            case "成都" -> 510100;
            case "杭州" -> 330100;
            case "武汉" -> 420100;
            case "南京" -> 320100;
            case "西安" -> 610100;
            case "重庆" -> 500100;
            default -> 110100;
        };
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private record Poi(String name, String address, long lat, long lng) {
    }

    private record PendingOrder(
            String senderJson,
            String recipientJson,
            String goodsJson,
            String businessType,
            String bizTypeSceneTag,
            String businessTypeTag,
            String tipFee,
            String purchaseDetail,
            String remark,
            String conversationId,
            String shop,
            String recipient,
            String phone,
            String item,
            long createdAt
    ) {
    }

    private static String mergeConversation(AgentTask task, String request) {
        StringBuilder merged = new StringBuilder();
        Object historyObject = task.parameters().get("history");
        if (historyObject instanceof List<?> history) {
            int start = Math.max(0, history.size() - 8);
            for (int i = start; i < history.size(); i++) {
                Object item = history.get(i);
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> map) {
                    Object role = map.get("role");
                    if (role != null && !"user".equalsIgnoreCase(role.toString())) {
                        continue;
                    }
                    Object content = map.get("content");
                    if (content != null) {
                        merged.append(content).append('\n');
                    }
                } else if (item instanceof ChatRequest.Message message && message.getContent() != null) {
                    if (message.getRole() != null && !"user".equalsIgnoreCase(message.getRole())) {
                        continue;
                    }
                    merged.append(message.getContent()).append('\n');
                } else {
                    merged.append(item).append('\n');
                }
            }
        }
        merged.append(request);
        return merged.toString();
    }

    private static String inferCity(String text) {
        if (text == null || text.isBlank()) {
            return "杭州";
        }
        for (String city : List.of("北京", "上海", "广州", "深圳", "成都", "杭州", "武汉", "南京", "西安", "重庆")) {
            if (text.contains(city)) {
                return city;
            }
        }
        return "杭州";
    }

    private static String extractRecipientKeyword(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] markers = {"配送至", "送到", "收货地址", "地址"};
        for (String marker : markers) {
            int index = text.lastIndexOf(marker);
            if (index >= 0) {
                return cleanRecipientKeyword(text.substring(index + marker.length()));
            }
        }
        return cleanRecipientKeyword(text);
    }

    private static String cleanRecipientKeyword(String value) {
        if (value == null) {
            return "";
        }
        String addressOnly = value
                .replaceAll("(?s)(?:联系电话|电话|手机号).*$", "")
                .replaceAll("(?s)1[3-9]\\d{9}.*$", "")
                .replaceAll("(?s)(?:购买门店|门店)(?:为|是)?[:：\\s].*$", "")
                .replaceAll("(?:的)?费用预览$", "");
        return cleanKeyword(addressOnly);
    }

    private static String extractShopKeyword(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int luckinIndex = text.lastIndexOf("瑞幸");
        if (luckinIndex < 0) {
            return hasPurchasePreference(text) ? cleanKeyword(text) : "瑞幸咖啡";
        }
        int end = text.indexOf('\n', luckinIndex);
        String candidate = end > luckinIndex ? text.substring(luckinIndex, end) : text.substring(luckinIndex);
        int storeEnd = candidate.indexOf("店");
        if (storeEnd >= 0) {
            candidate = candidate.substring(0, storeEnd + 1);
        }
        return cleanKeyword(candidate);
    }

    private static String cleanKeyword(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replace("。", " ")
                .replace("，", " ")
                .replace(",", " ")
                .replace("；", " ")
                .replace(";", " ")
                .replace("：", " ")
                .replace(":", " ")
                .replace("，", " ")
                .trim();
        int maxLength = Math.min(cleaned.length(), 80);
        return cleaned.substring(0, maxLength).trim();
    }

    private static boolean isToolError(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        String normalized = result.toLowerCase();
        return result.contains("\"status\":\"error\"")
                || result.contains("失败")
                || result.contains("未登录")
                || result.contains("登录")
                || normalized.contains("error")
                || normalized.contains("eacces");
    }

    private static boolean isAuthError(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String normalized = result.toLowerCase();
        return normalized.contains("[error 401]")
                || normalized.contains("\"code\":401")
                || normalized.contains("401 unauthorized")
                || result.contains("未授权")
                || result.contains("AUTH_FAILED")
                || normalized.contains("unauthorized");
    }

    private static boolean isPreviewConfirmation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("确认预览") || text.contains("预览") || text.contains("确认");
    }

    private static boolean hasPhone(String text) {
        return text != null && PHONE_PATTERN.matcher(text).find();
    }

    private static boolean hasRecipientAddress(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("送到") || text.contains("配送至") || text.contains("收货")
                || text.contains("地址") || text.contains("区") || text.contains("园区")
                || text.contains("小区") || text.contains("大厦") || text.contains("楼");
    }

    private static boolean hasSpecificItem(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("拿铁") || text.contains("美式") || text.contains("瑞纳冰")
                || text.contains("生椰") || text.contains("厚乳") || text.contains("中杯")
                || text.contains("大杯") || text.contains("少冰") || text.contains("七分糖");
    }

    private static boolean hasPurchasePreference(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("店") || text.contains("就近") || text.contains("附近")
                || text.contains("指定门店") || text.contains("购买地址");
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
