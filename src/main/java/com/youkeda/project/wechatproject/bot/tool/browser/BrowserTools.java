package com.youkeda.project.wechatproject.bot.tool.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.project.wechatproject.bot.tool.ToolService;
import com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Browser automation tools exposed as Spring AI {@link Tool} methods.
 * Wraps chrome-devtools-mcp tools for the AI agent to control a browser.
 *
 * <p>All URLs are validated through {@link BrowserSecurityPolicy} before navigation.
 * Network response headers with sensitive data (Cookie, Authorization, etc.) are redacted.
 * Page count is tracked to enforce the maxPages limit.</p>
 */
public class BrowserTools implements ToolService.ProjectTool {

    @Override
    public String category() {
        return "browser";
    }

    private static final Logger log = LoggerFactory.getLogger(BrowserTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<List<byte[]>> PENDING_SCREENSHOTS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<String> TEXT_PAYLOAD = new ThreadLocal<>();

    /** Drain all screenshots captured by {@link #screenshot()} during this tool execution. */
    public static List<byte[]> drainScreenshots() {
        List<byte[]> screenshots = PENDING_SCREENSHOTS.get();
        if (screenshots.isEmpty()) return List.of();
        List<byte[]> copy = List.copyOf(screenshots);
        screenshots.clear();
        return copy;
    }

    /** Set by the router before tool execution. */
    public static void setCurrentUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT_USER.set(userId);
        }
    }

    /** Clear by the router after tool execution. */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    public static void prepareTextPayload(String text) {
        if (text != null && !text.isBlank()) {
            TEXT_PAYLOAD.set(text);
        }
    }

    public static void clearTextPayload() {
        TEXT_PAYLOAD.remove();
    }

    private String currentUserId() {
        String uid = CURRENT_USER.get();
        return uid != null ? uid : "unknown";
    }

    private final BrowserMcpClient client;
    private final BrowserMcpProcess process;
    private final BrowserSecurityPolicy securityPolicy;
    private final BrowserMcpProperties properties;
    private final BrowserAuditLogger auditLogger;
    private final AtomicInteger pageCount = new AtomicInteger(1);
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private final AtomicInteger successfulCallCount = new AtomicInteger(0);
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final int MAX_TOOL_RESULT_TOKENS = 5_000;

    // Sensitive headers to redact from network responses
    private static final String[] SENSITIVE_HEADERS = {
            "cookie", "set-cookie", "authorization", "x-api-key",
            "x-auth-token", "access-token", "refresh-token", "session"
    };

    public BrowserTools(BrowserMcpClient client, BrowserMcpProcess process,
                        BrowserSecurityPolicy securityPolicy, BrowserMcpProperties properties,
                        BrowserAuditLogger auditLogger) {
        this.client = client;
        this.process = process;
        this.securityPolicy = securityPolicy;
        this.properties = properties;
        this.auditLogger = auditLogger;
        log.info("BrowserTools initialized: {} tools, headless={}, maxPages={}",
                18, properties.isHeadless(), properties.getMaxPages());
    }

    // -------------------------------------------------------------------------
    // Process health
    // -------------------------------------------------------------------------

    private void ensureProcessAlive() throws IOException {
        if (!process.isAlive()) {
            int attempts = restartAttempts.incrementAndGet();
            if (attempts > MAX_RESTART_ATTEMPTS) {
                throw new BrowserMcpException("PROCESS_DEAD",
                        "浏览器进程已崩溃多次（最近 " + MAX_RESTART_ATTEMPTS + " 次调用均失败），"
                                + "请重启应用。诊断: " + process.getDiagnostics());
            }
            log.warn("chrome-devtools-mcp process died, attempting restart ({}/{}) {}",
                    attempts, MAX_RESTART_ATTEMPTS, process.getDiagnostics());

            try {
                process.restart();
            } catch (BrowserMcpException e) {
                // restart() detected cooldown — propagate immediately
                throw new IOException(e.getMessage(), e);
            } catch (IOException e) {
                log.error("chrome-devtools-mcp restart failed: {}", e.getMessage());
                throw new IOException("浏览器进程启动失败: " + e.getMessage(), e);
            }

            // Verify the restarted process is alive before initializing
            if (!process.isAlive()) {
                throw new IOException("浏览器进程启动后立即退出。诊断: " + process.getDiagnostics());
            }

            try {
                client.initialize();
            } catch (IOException e) {
                log.error("MCP initialize failed after restart: {}", e.getMessage());
                throw new IOException("MCP 初始化失败（进程可能启动后崩溃）: " + e.getMessage(), e);
            }
        }
    }

    private String safeCall(String toolName, JsonNode arguments, String urlForAudit) {
        long start = System.currentTimeMillis();
        String userId = currentUserId();
        try {
            ensureProcessAlive();
            String result = client.callTool(toolName, arguments);
            // Successful tool call — reset restart tracking
            restartAttempts.set(0);
            process.markActivity();
            if (process.getConsecutiveStartupFailures() > 0
                    && successfulCallCount.incrementAndGet() >= 2) {
                // After 2 successful calls, reset startup failure counter
                // (the process has proven stable)
            }
            auditLogger.logAction(userId, toolName, urlForAudit,
                    System.currentTimeMillis() - start, true, "OK");
            return TokenBudgetUtil.truncateAtBoundary(result, MAX_TOOL_RESULT_TOKENS);
        } catch (Exception e) {
            auditLogger.logAction(userId, toolName, urlForAudit,
                    System.currentTimeMillis() - start, false, e.getMessage());
            auditLogger.logError(userId, toolName, urlForAudit, e);
            return "Error: " + toolName + " failed: " + e.getMessage()
                    + ". Retry with adjusted arguments or report the failure to the user.";
        }
    }

    // -------------------------------------------------------------------------
    // Programmatic capture (not @Tool — used by BrowserAgent for verification)
    // -------------------------------------------------------------------------

    /**
     * Capture a screenshot directly, returning the raw PNG bytes.
     * Used by BrowserAgent for task verification. Does NOT use PENDING_SCREENSHOTS.
     */
    public byte[] captureScreenshotBytes() throws IOException {
        ensureProcessAlive();
        ObjectNode args = MAPPER.createObjectNode();
        JsonNode result = client.callToolRaw("take_screenshot", args);
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                JsonNode data = item.path("data");
                if (!data.isMissingNode()) {
                    return Base64.getDecoder().decode(data.asText());
                }
            }
        }
        throw new IOException("take_screenshot returned no image data");
    }

    /**
     * Capture the accessibility snapshot as plain text.
     * Used by BrowserAgent for task verification.
     */
    public String captureSnapshotText() throws IOException {
        ensureProcessAlive();
        ObjectNode args = MAPPER.createObjectNode();
        return client.callTool("take_snapshot", args);
    }

    // -------------------------------------------------------------------------
    // Navigation tools
    // -------------------------------------------------------------------------

    @Tool(name = "browser_navigate",
          description = "在浏览器中导航到指定URL。用于打开网页、跳转页面。URL会自动补充https://前缀。"
                      + "注意：只能访问公网地址，无法访问内网IP（如127.0.0.1、192.168.x.x等）。")
    public String navigate(@ToolParam(description = "目标URL，例如 www.baidu.com 或 https://example.com") String url) {
        securityPolicy.validateUrl(url);
        String normalized = url.trim();
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }

        ObjectNode args = MAPPER.createObjectNode();
        args.put("type", "url");
        args.put("url", normalized);

        pageCount.set(1);
        return safeCall("navigate_page", args, normalized);
    }

    @Tool(name = "browser_new_page",
          description = "新建一个浏览器标签页并导航到指定URL。最多同时打开5个标签页。")
    public String newPage(@ToolParam(description = "目标URL") String url) {
        securityPolicy.validateUrl(url);
        String normalized = url.trim();
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }

        if (pageCount.get() >= properties.getMaxPages()) {
            return "已达到最大标签页数限制（" + properties.getMaxPages() + "），请先关闭一些标签页。";
        }

        ObjectNode args = MAPPER.createObjectNode();
        args.put("url", normalized);

        pageCount.incrementAndGet();
        return safeCall("new_page", args, normalized);
    }

    @Tool(name = "browser_wait_for",
          description = "等待页面上出现指定文本后再继续。用于等待页面加载完成或特定内容出现。")
    public String waitFor(@ToolParam(description = "等待出现的文本内容") String text) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("text", text);

        return safeCall("wait_for", args, "wait_for:" + text);
    }

    // -------------------------------------------------------------------------
    // Page management
    // -------------------------------------------------------------------------

    @Tool(name = "browser_list_pages",
          description = "列出浏览器中所有打开的标签页，返回每页的索引、标题和URL。")
    public String listPages() {
        ObjectNode args = MAPPER.createObjectNode();
        return safeCall("list_pages", args, "list_pages");
    }

    @Tool(name = "browser_select_page",
          description = "切换到指定索引的标签页。")
    public String selectPage(@ToolParam(description = "标签页索引，从0开始") int pageIdx) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pageIdx", pageIdx);

        return safeCall("select_page", args, "select_page:" + pageIdx);
    }

    @Tool(name = "browser_close_page",
          description = "关闭指定索引的标签页。")
    public String closePage(@ToolParam(description = "标签页索引，从0开始") int pageIdx) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pageIdx", pageIdx);

        pageCount.decrementAndGet();
        return safeCall("close_page", args, "close_page:" + pageIdx);
    }

    // -------------------------------------------------------------------------
    // Interaction tools
    // -------------------------------------------------------------------------

    @Tool(name = "browser_click",
          description = "点击页面上的元素。uid来自browser_snapshot返回的元素标识。")
    public String click(@ToolParam(description = "元素的uid标识，来自browser_snapshot的结果") String uid) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("uid", uid);

        return safeCall("click", args, "click:" + uid);
    }

    @Tool(name = "browser_fill",
          description = "在输入框(INPUT/TEXTAREA/SELECT)中填写文本内容。uid来自browser_snapshot返回的元素标识。"
                      + "注意：此工具适用于普通表单元素。对于富文本编辑器(如语雀、微信公众号编辑器等contenteditable区域)，"
                      + "请先使用browser_click点击编辑器区域使其获得焦点，然后使用browser_type_text逐字输入文本。")
    public String fill(@ToolParam(description = "输入框元素的uid标识") String uid,
                       @ToolParam(description = "要填写的文本值") String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("uid", uid);
        args.put("value", value);

        return safeCall("fill", args, "fill:" + uid);
    }

    @Tool(name = "browser_type_text",
          description = "通过键盘逐字输入文本。适用于富文本编辑器(如语雀、微信公众号等contenteditable区域)。"
                      + "使用时需要先用browser_click点击编辑器区域使其获得焦点，再调用此工具输入内容。"
                      + "可选参数submitKey：输入完成后自动按下的键，如\"Enter\"、\"Tab\"。")
    public String typeText(@ToolParam(description = "要输入的文本内容") String text,
                           @ToolParam(description = "可选：输入完成后按下的键，如Enter、Tab", required = false) String submitKey) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("text", text);
        if (submitKey != null && !submitKey.isBlank()) {
            args.put("submitKey", submitKey);
        }

        return safeCall("type_text", args, "type_text:" + text);
    }

    @Tool(name = "browser_press_key",
          description = "按下键盘按键或组合键。例如 Enter、Escape、Control+A、Control+C等。")
    public String pressKey(@ToolParam(description = "按键名称或组合键，如 Enter、Escape、Control+A") String key) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("key", key);

        return safeCall("press_key", args, "press_key:" + key);
    }

    // -------------------------------------------------------------------------
    // Debugging / Content extraction
    // -------------------------------------------------------------------------

    @Tool(name = "browser_snapshot",
          description = "获取当前页面的文本内容快照（基于无障碍树a11y tree）。"
                      + "返回页面上的文本、链接、按钮、输入框等元素及其uid标识。"
                      + "这是获取页面内容的主要方式，优先于截图使用，因为文本Token消耗更少。")
    public String snapshot() {
        ObjectNode args = MAPPER.createObjectNode();
        return safeCall("take_snapshot", args, "take_snapshot");
    }

    @Tool(name = "browser_screenshot",
          description = "对当前页面截图。截图会自动暂存，可通过 send_message_to_user 发送给用户。"
                      + "仅在需要视觉判断或需要展示给用户时使用。")
    public String screenshot() {
        ObjectNode args = MAPPER.createObjectNode();
        long start = System.currentTimeMillis();
        try {
            ensureProcessAlive();
            JsonNode result = client.callToolRaw("take_screenshot", args);
            // Extract base64 image data from MCP response (content[].data field)
            JsonNode content = result.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    JsonNode data = item.path("data");
                    if (!data.isMissingNode()) {
                        byte[] bytes = Base64.getDecoder().decode(data.asText());
                        PENDING_SCREENSHOTS.get().add(bytes);
                    }
                }
            }
            restartAttempts.set(0);
            process.markActivity();
            int count = PENDING_SCREENSHOTS.get().size();
            log.info("browser_screenshot captured, total pending: {}", count);
            return "截图已捕获（共 " + count + " 张待发送）。使用 send_message_to_user 将截图发送给用户。";
        } catch (IOException e) {
            log.error("browser_screenshot failed: {}", e.getMessage());
            throw new RuntimeException("截图失败：" + e.getMessage(), e);
        }
    }

    @Tool(name = "browser_list_console_messages",
          description = "获取浏览器控制台的日志消息（console.log, console.error等）。")
    public String listConsoleMessages() {
        ObjectNode args = MAPPER.createObjectNode();
        return safeCall("list_console_messages", args, "list_console_messages");
    }

    // -------------------------------------------------------------------------
    // Network inspection (抓包)
    // -------------------------------------------------------------------------

    @Tool(name = "browser_list_network_requests",
          description = "列出当前页面上发起的所有网络请求（XHR/Fetch/资源请求）。"
                      + "返回请求ID、URL、HTTP方法、状态码、响应大小等信息。"
                      + "用于抓包分析页面发出的API请求。注意：响应头中包含的Cookie、Authorization等敏感信息会被自动脱敏。")
    public String listNetworkRequests() {
        ObjectNode args = MAPPER.createObjectNode();
        String raw = safeCall("list_network_requests", args, "list_network_requests");
        return redactSensitiveHeaders(raw);
    }

    @Tool(name = "browser_get_network_request",
          description = "获取某个网络请求的详细信息，包括请求头和响应体。"
                      + "reqid来自browser_list_network_requests返回的请求ID。"
                      + "注意：Cookie、Authorization等敏感请求头会被自动脱敏。")
    public String getNetworkRequest(@ToolParam(description = "网络请求ID，来自browser_list_network_requests") String reqid) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("reqid", reqid);

        String raw = safeCall("get_network_request", args, "get_network_request:" + reqid);
        return redactSensitiveHeaders(raw);
    }

    // -------------------------------------------------------------------------
    // Script evaluation
    // -------------------------------------------------------------------------

    @Tool(name = "browser_evaluate_script",
          description = "在当前页面执行JavaScript脚本并返回结果。"
                      + "高级调试工具，默认关闭。仅允许读取DOM内容的操作（如querySelector、innerText、textContent）。"
                      + "禁止修改页面、访问localStorage/Cookie、发起网络请求。写富文本请使用browser_set_rich_text。")
    public String evaluateScript(@ToolParam(description = "要执行的JavaScript脚本（只读操作）") String script) {
        securityPolicy.validateScript(script);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("function", script);

        return safeCall("evaluate_script", args, "evaluate_script");
    }

    @Tool(name = "browser_set_rich_text",
          description = "向富文本编辑器或普通输入框写入正文。用于语雀、微信公众号、飞书等contenteditable/ProseMirror编辑器。"
                      + "这是受控写入工具：只接收纯文本和可选CSS选择器，不允许模型执行任意JavaScript。"
                      + "如果不知道选择器，可先不传selector，工具会自动尝试常见编辑器选择器。")
    public String setRichText(
            @ToolParam(description = "要写入编辑器的纯文本正文。换行会被保留。") String text,
            @ToolParam(required = false, description = "可选CSS选择器，例如 [contenteditable=\"true\"]、.ProseMirror、[role=\"textbox\"]。") String selector) {
        securityPolicy.validateRichTextWrite(text, selector);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("function", buildSetRichTextFunction(text, selector));

        return safeCall("evaluate_script", args, "set_rich_text");
    }

    @Tool(name = "browser_set_text_payload",
          description = "将系统预置的大文本正文写入富文本编辑器或普通输入框。"
                      + "当任务指令提到LAST_CHAT_TEXT、上一步正文、文章正文、payload正文时优先使用此工具，"
                      + "避免把长正文复制到工具参数里。可选selector规则同browser_set_rich_text。")
    public String setTextPayload(
            @ToolParam(required = false, description = "可选CSS选择器，例如 [contenteditable=\"true\"]、.ProseMirror、[role=\"textbox\"]。") String selector) {
        String text = TEXT_PAYLOAD.get();
        if (text == null || text.isBlank()) {
            return "NO_TEXT_PAYLOAD_AVAILABLE";
        }
        securityPolicy.validateRichTextWrite(text, selector);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("function", buildSetRichTextFunction(text, selector));
        return safeCall("evaluate_script", args, "set_text_payload");
    }

    private static String buildSetRichTextFunction(String text, String selector) {
        String textJson = toJsonString(text != null ? text : "");
        String selectorJson = selector == null || selector.isBlank() ? "null" : toJsonString(selector.trim());
        return """
                () => {
                  const text = %s;
                  const selector = %s;
                  let selected = null;
                  if (selector) {
                    try {
                      selected = document.querySelector(selector);
                    } catch (e) {
                      return 'INVALID_SELECTOR: ' + e.message;
                    }
                  }
                  const candidates = selected
                    ? [selected]
                    : Array.from(document.querySelectorAll('[contenteditable="true"], .ProseMirror, [role="textbox"], textarea, input[type="text"], input:not([type])'));
                  const editor = candidates.find(el => el && (
                    el.isContentEditable ||
                    el.getAttribute?.('contenteditable') === 'true' ||
                    el.getAttribute?.('role') === 'textbox' ||
                    el.matches?.('textarea,input')
                  ));
                  if (!editor) {
                    return 'NO_EDITOR_FOUND';
                  }
                  editor.focus();
                  if (editor.matches?.('textarea,input')) {
                    editor.value = text;
                  } else {
                    const fragment = document.createDocumentFragment();
                    text.split('\\n').forEach((line, index) => {
                      if (index > 0) {
                        fragment.appendChild(document.createElement('br'));
                      }
                      fragment.appendChild(document.createTextNode(line));
                    });
                    editor.replaceChildren(fragment);
                  }
                  try {
                    editor.dispatchEvent(new InputEvent('input', {
                      bubbles: true,
                      composed: true,
                      inputType: 'insertText',
                      data: text
                    }));
                  } catch (e) {
                    editor.dispatchEvent(new Event('input', { bubbles: true }));
                  }
                  editor.dispatchEvent(new Event('change', { bubbles: true }));
                  return 'OK';
                }
                """.formatted(textJson, selectorJson);
    }

    private static String toJsonString(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to encode script string", e);
        }
    }

    // -------------------------------------------------------------------------
    // Human login flow
    // -------------------------------------------------------------------------

    @Tool(name = "browser_request_human_login",
          description = "【登录场景首选】打开需要登录的网页，通知人类用户手动完成登录。"
                      + "当你需要让用户登录某个网站时，优先使用此工具而非browser_navigate。"
                      + "适用场景：需要用户名密码登录、手机扫码登录、验证码输入等。"
                      + "此工具会自动导航到登录页面，并生成提示消息告知用户如何登录。"
                      + "用户表示登录完成后，必须调用browser_snapshot检查页面确认登录状态。")
    public String requestHumanLogin(
            @ToolParam(description = "登录页面的URL") String url,
            @ToolParam(description = "为什么需要登录，用于告知用户") String reason) {
        securityPolicy.validateUrl(url);
        securityPolicy.checkLoginRequest();

        String normalized = url.trim();
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }

        ObjectNode args = MAPPER.createObjectNode();
        args.put("type", "url");
        args.put("url", normalized);

        String result = safeCall("navigate_page", args, normalized);

        return "已导航至登录页面：" + normalized
                + "\n登录原因：" + reason
                + "\n请在浏览器窗口中手动完成登录（输入密码或扫码）。"
                + "\n登录完成后请回复'已登录'或'登录完成'，我将通过browser_snapshot确认登录状态并继续自动化操作。";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Redact sensitive headers (Cookie, Authorization, etc.) from network response text.
     * The raw text from chrome-devtools-mcp may contain full request headers.
     */
    static String redactSensitiveHeaders(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String result = raw;
        for (String header : SENSITIVE_HEADERS) {
            // Match header patterns like "header-name: value" or "\"header-name\": \"value\""
            result = result.replaceAll(
                    "(?i)(\"" + Pattern.quote(header) + "\"\\s*:\\s*\")[^\"]+(\")",
                    "$1***REDACTED***$2"
            );
            result = result.replaceAll(
                    "(?i)(^\\s*" + Pattern.quote(header) + "\\s*:\\s*).+$",
                    "$1***REDACTED***"
            );
        }
        return result;
    }

    // Import for Pattern.quote in redactSensitiveHeaders
    private static final class Pattern {
        static String quote(String s) {
            return java.util.regex.Pattern.quote(s);
        }
    }
}
