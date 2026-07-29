package com.youkeda.project.wechatproject.bot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Browser Agent — lets the LLM control a real browser (headless Chromium via Playwright).
 *
 * <p>The agent can navigate to URLs, click elements, type into forms,
 * scroll pages, execute JavaScript, extract content, and take screenshots.
 * Each WeChat user gets an isolated browser context (separate cookies/storage).
 *
 * <p>Enable with: {@code agent.tools.browser.enabled=true}
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.browser", name = "enabled", havingValue = "true")
public class BrowserAgentTool implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgentTool.class);

    @Value("${agent.tools.browser.headless:true}")
    private boolean headless;

    private BrowserAgentService browserService;

    public BrowserAgentTool() {
        // no-arg constructor required by Spring — init in @PostConstruct
    }

    @PostConstruct
    void init() {
        this.browserService = new BrowserAgentService(headless);
        log.info("BrowserAgentTool initialized: headless={}", headless);
    }

    /** Exposed for testing. */
    BrowserAgentTool(BrowserAgentService browserService) {
        this.browserService = browserService;
    }

    @Override
    public String category() {
        return "browser";
    }

    /**
     * Resolves the current user ID. In the WeChat bot context, this is available
     * via a ThreadLocal set by the message handler. Falls back to "default".
     */
    private static String currentUserId() {
        String id = DiDiTaxiTools.getCurrentUser();
        return id != null ? "browser-" + id : "browser-default";
    }

    // ── Tool methods ────────────────────────────────────────────

    @Tool(name = "browser_navigate",
            description = "Navigate the browser to a URL. Returns page title, visible text content, and interactive elements (links, buttons, inputs). Use this first when the user asks you to browse a website, search on a search engine, or visit a specific page.")
    public String navigate(
            @ToolParam(description = "The full URL to navigate to, e.g. https://www.baidu.com/s?wd=keyword") String url) {
        log.info("browser_navigate: url={}", url);
        if (url == null || url.isBlank()) {
            return "Error: URL is required.";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            return browserService.navigate(currentUserId(), url);
        } catch (Exception e) {
            log.warn("browser_navigate failed: url={}, error={}", url, e.getMessage());
            return "Browser navigate failed: " + e.getMessage();
        }
    }

    @Tool(name = "browser_click",
            description = "Click an element on the current page by CSS selector or visible text. Use after browser_navigate or browser_get_content to interact with links, buttons, or other clickable elements. Returns the updated page state after the click. IMPORTANT: you can use plain Chinese/English button text directly as the selector, e.g. '发送验证码', '登录', '获取验证码' — no CSS syntax needed.")
    public String click(
            @ToolParam(description = "CSS selector OR plain button/link text. For CSS use '#id' or '.class'. For plain text just use the exact visible text like '发送验证码', '登录', '下一页'. Using the exact visible text is the preferred and most reliable approach.") String selector) {
        log.info("browser_click: selector={}", selector);
        if (selector == null || selector.isBlank()) {
            return "Error: selector is required.";
        }
        try {
            return browserService.click(currentUserId(), selector);
        } catch (Exception e) {
            log.warn("browser_click failed: selector={}, error={}", selector, e.getMessage());
            return "Browser click failed on '" + selector + "': " + e.getMessage()
                    + ". Try using browser_get_content to see current page state and clickable elements.";
        }
    }

    @Tool(name = "browser_type",
            description = "Type text into an input field on the current page. Use after browser_navigate to fill forms, search boxes, or text areas. Returns the updated page state so you can verify the input was entered correctly. You can use plain text from the input's placeholder or label as the selector, e.g. '手机号', '验证码'.")
    public String type(
            @ToolParam(description = "CSS selector OR input placeholder/label text. For CSS use 'input[name=\"phone\"]'. For text use the input's placeholder like '手机号', '验证码', or label text. Using placeholder text is the most reliable approach for form inputs.") String selector,
            @ToolParam(description = "The text to type into the input field") String text) {
        log.info("browser_type: selector={}, text={}", selector, text);
        if (selector == null || selector.isBlank()) {
            return "Error: selector is required.";
        }
        if (text == null) {
            text = "";
        }
        try {
            return browserService.type(currentUserId(), selector, text);
        } catch (Exception e) {
            log.warn("browser_type failed: selector={}, error={}", selector, e.getMessage());
            return "Browser type failed on '" + selector + "': " + e.getMessage()
                    + ". Try using browser_get_content to see current page state and available inputs.";
        }
    }

    @Tool(name = "browser_get_content",
            description = "Get the current page's text content, title, URL, and list of interactive elements (links, buttons, inputs). Use this to understand what's on the page after navigation or interaction, or when you need to find specific elements to interact with.")
    public String getContent() {
        log.info("browser_get_content invoked");
        try {
            return browserService.getContent(currentUserId());
        } catch (Exception e) {
            log.warn("browser_get_content failed: {}", e.getMessage());
            return "Browser get content failed: " + e.getMessage();
        }
    }

    @Tool(name = "browser_screenshot",
            description = "Take a screenshot of the current browser page. Returns a base64-encoded image of the visible viewport. Use when you need to visually inspect a page, verify layout, or capture visual information that text extraction can't represent (charts, images, CAPTCHAs, etc.).")
    public String screenshot() {
        log.info("browser_screenshot invoked");
        try {
            return browserService.screenshot(currentUserId());
        } catch (Exception e) {
            log.warn("browser_screenshot failed: {}", e.getMessage());
            return "Browser screenshot failed: " + e.getMessage();
        }
    }

    @Tool(name = "browser_scroll",
            description = "Scroll the current page up or down. Use when the content you need is below the fold and not visible in the initial page state. 'down' scrolls toward the bottom, 'up' scrolls toward the top.")
    public String scroll(
            @ToolParam(description = "Scroll direction: 'down' to scroll toward the bottom, 'up' to scroll toward the top") String direction) {
        log.info("browser_scroll: direction={}", direction);
        try {
            return browserService.scroll(currentUserId(), direction != null ? direction : "down");
        } catch (Exception e) {
            log.warn("browser_scroll failed: {}", e.getMessage());
            return "Browser scroll failed: " + e.getMessage();
        }
    }

    @Tool(name = "browser_go_back",
            description = "Go back to the previous page in browser history. Use when the user wants to return to a previous page or when you navigated to the wrong page.")
    public String goBack() {
        log.info("browser_go_back invoked");
        try {
            return browserService.goBack(currentUserId());
        } catch (Exception e) {
            log.warn("browser_go_back failed: {}", e.getMessage());
            return "Browser go back failed: " + e.getMessage();
        }
    }

    @Tool(name = "browser_execute_js",
            description = "Execute arbitrary JavaScript code in the browser context and return the result. Use for advanced interactions that can't be done with click/type/scroll (e.g., extracting specific data, manipulating page state, triggering custom events).")
    public String executeJs(
            @ToolParam(description = "JavaScript code to execute in the page context. The return value will be captured and returned.") String script) {
        log.info("browser_execute_js: script={}", script != null ? script.substring(0, Math.min(80, script.length())) : "null");
        if (script == null || script.isBlank()) {
            return "Error: script is required.";
        }
        try {
            return browserService.executeJs(currentUserId(), script);
        } catch (Exception e) {
            log.warn("browser_execute_js failed: {}", e.getMessage());
            return "Browser execute JS failed: " + e.getMessage();
        }
    }

    // ── Login helpers ────────────────────────────────────────────

    @Tool(name = "browser_get_qrcode",
            description = "Extract the QR code image from the current page and return it as a base64 image. Use this when the user needs to scan a QR code to log in. It searches for QR-related elements (img with qr/qrcode class, canvas elements) and clips a screenshot of the QR code area. If no specific QR element is found, it returns a full-page screenshot.")
    public String getQrCode() {
        log.info("browser_get_qrcode invoked");
        try {
            return browserService.getQrCode(currentUserId());
        } catch (Exception e) {
            log.warn("browser_get_qrcode failed: {}", e.getMessage());
            return "Failed to get QR code: " + e.getMessage();
        }
    }

    @Tool(name = "browser_wait_for_login",
            description = "Wait for the page to change after the user scans a QR code or submits a verification code. Polls every 2 seconds until the URL changes (indicating a redirect after successful login) or a logged-in indicator appears on the page. Use this after telling the user to scan a QR code or enter a verification code. Max wait time can be specified (default 30s, max 120s).")
    public String waitForLogin(
            @ToolParam(description = "Maximum seconds to wait (1-120, default 30)") Integer timeoutSeconds) {
        int timeout = timeoutSeconds != null ? timeoutSeconds : 30;
        log.info("browser_wait_for_login: timeout={}s", timeout);
        try {
            return browserService.waitForPageChange(currentUserId(), timeout);
        } catch (Exception e) {
            log.warn("browser_wait_for_login failed: {}", e.getMessage());
            return "Wait for login failed: " + e.getMessage();
        }
    }
}
