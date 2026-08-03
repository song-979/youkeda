package com.youkeda.project.wechatproject.bot.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Playwright browser instances and per-user isolated browser contexts.
 *
 * <p>Each WeChat user gets their own {@link BrowserContext}, which provides
 * isolated cookies, localStorage, and session state. Contexts auto-expire
 * after a period of inactivity.
 */
public class BrowserAgentService {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgentService.class);

    private static final long CONTEXT_TTL_MS = 1_800_000;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int PAGE_READY_TIMEOUT_MS = 5_000;
    private static final Path SCREENSHOT_DIR = Path.of("data/browser-screenshots");
    private static final Path STORAGE_DIR = Path.of("data/browser-cookies");

    // Thread-safe queue for browser-generated images to be picked up by the reply pipeline.
    // When browser_screenshot or browser_get_qrcode produce an image, it is stored here
    // (instead of being returned as base64 text), and OrchestrationService.toModelReply()
    // drains the queue to include the images in the response.
    private static final java.util.Queue<BrowserImage> pendingBrowserImages =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public record BrowserImage(byte[] bytes, String fileName, String description) {}

    /** Drain all pending browser images for the current response. */
    public static List<BrowserImage> drainBrowserImages() {
        List<BrowserImage> images = new ArrayList<>();
        BrowserImage img;
        while ((img = pendingBrowserImages.poll()) != null) {
            images.add(img);
        }
        return images;
    }

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Map<String, UserContext> contexts = new ConcurrentHashMap<>();
    private final boolean headless;

    public BrowserAgentService() {
        this(true);
    }

    public BrowserAgentService(boolean headless) {
        this.headless = headless;
        try {
            Files.createDirectories(SCREENSHOT_DIR);
            Files.createDirectories(STORAGE_DIR);
        } catch (Exception e) {
            throw new RuntimeException("failed to create browser data directory", e);
        }

        java.util.concurrent.ScheduledExecutorService cleaner =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "browser-cleaner");
                    t.setDaemon(true);
                    return t;
                });
        // Cleanup expired contexts
        cleaner.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            contexts.entrySet().removeIf(entry -> {
                if (now - entry.getValue().lastAccess > CONTEXT_TTL_MS) {
                    closeContext(entry.getKey(), entry.getValue());
                    return true;
                }
                return false;
            });
        }, 120, 120, java.util.concurrent.TimeUnit.SECONDS);
        // Periodic save — persist cookies every 60s so they survive restarts
        cleaner.scheduleWithFixedDelay(this::saveAllStates, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        // Shutdown hook — save all states on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveAllStates, "browser-shutdown"));
    }

    private Browser getBrowser() {
        Browser current = browser;
        if (isBrowserAlive(current)) {
            return current;
        }
        synchronized (this) {
            if (!isBrowserAlive(browser)) {
                closeBrowserQuietly();
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setArgs(List.of(
                                "--no-sandbox",
                                "--disable-dev-shm-usage",
                                "--disable-gpu",
                                "--disable-blink-features=AutomationControlled",
                                "--disable-features=IsolateOrigins,site-per-process",
                                "--disable-site-isolation-trials",
                                "--disable-features=BackForwardCache"
                        )));
                log.info("Playwright browser launched (headless={})", headless);
            }
            return browser;
        }
    }

    // ── Navigation ──────────────────────────────────────────────

    private boolean isBrowserAlive(Browser candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            return candidate.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void closeBrowserQuietly() {
        Browser oldBrowser = browser;
        browser = null;
        try {
            if (oldBrowser != null) {
                oldBrowser.close();
            }
        } catch (Exception e) {
            log.debug("ignored error while closing stale browser: {}", e.getMessage());
        }

        Playwright oldPlaywright = playwright;
        playwright = null;
        try {
            if (oldPlaywright != null) {
                oldPlaywright.close();
            }
        } catch (Exception e) {
            log.debug("ignored error while closing stale playwright: {}", e.getMessage());
        }
    }

    public String navigate(String userId, String url) {
        Page page = getOrCreatePage(userId);
        try {
            return navigateOnce(page, url, "navigated to " + url);
        } catch (TimeoutError e) {
            log.warn("browser navigation timed out for user={}, url={}: {}", userId, url, e.getMessage());
            return capturePageState(page, "navigation timed out after partial load: " + url);
        } catch (RuntimeException e) {
            if (!isTargetClosedError(e)) {
                throw e;
            }
            log.warn("browser target closed for user={}, recreating context and retrying navigation: {}",
                    userId, e.getMessage());
            discardUserContext(userId);
            Page retryPage = getOrCreatePage(userId);
            try {
                return navigateOnce(retryPage, url, "navigated to " + url + " after browser recovery");
            } catch (TimeoutError retryTimeout) {
                log.warn("browser navigation timed out after recovery for user={}, url={}: {}",
                        userId, url, retryTimeout.getMessage());
                return capturePageState(retryPage, "navigation timed out after browser recovery: " + url);
            }
        }
    }

    private String navigateOnce(Page page, String url, String action) {
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(DEFAULT_TIMEOUT_MS)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForPageReady(page);
        return capturePageState(page, action);
    }

    // ── Interaction ─────────────────────────────────────────────

    public String click(String userId, String selector) {
        Page page = getOrCreatePage(userId);
        Locator target = resolveLocator(page, selector);
        try {
            target.click(new Locator.ClickOptions()
                    .setForce(true).setTimeout(DEFAULT_TIMEOUT_MS));
        } catch (Exception e) {
            log.debug("click via locator failed for '{}', trying JS: {}", selector, e.getMessage());
            clickViaJs(page, selector);
        }
        waitForPageReady(page);
        return capturePageState(page, "clicked '" + selector + "'");
    }

    public String type(String userId, String selector, String text) {
        Page page = getOrCreatePage(userId);
        Locator target = resolveLocator(page, selector);
        try {
            target.fill(text, new Locator.FillOptions()
                    .setForce(true).setTimeout(DEFAULT_TIMEOUT_MS));
        } catch (Exception e) {
            log.debug("fill via locator failed for '{}', trying JS: {}", selector, e.getMessage());
            typeViaJs(page, selector, text);
        }
        return capturePageState(page, "typed into '" + selector + "'");
    }

    /**
     * Resolve a human-described selector (CSS or plain text) into a Playwright
     * Locator. Plain Chinese/English text like "发送验证码" or "登录" is treated
     * as a text selector so the LLM doesn't need to know CSS syntax.
     */
    private Locator resolveLocator(Page page, String selector) {
        // If it looks like a CSS selector (contains special chars), keep as-is
        if (looksLikeCss(selector)) {
            return page.locator(selector).first();
        }
        // Plain text — match by visible text, then by button value, then by placeholder
        return page.locator(":has-text('" + escapeJs(selector) + "'):not(html):not(body)").first();
    }

    private static boolean looksLikeCss(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("#.[]:>+~()=*^$,@".indexOf(c) >= 0) return true;
        }
        return false;
    }

    private void clickViaJs(Page page, String selector) {
        String escaped = escapeJs(selector);
        String script = looksLikeCss(selector)
                ? "() => { const el = document.querySelector('" + escaped + "'); if (el) el.click(); }"
                : "() => {\n"
                + "  const xpath = \"//*[text()='" + escaped + "']\";\n"
                + "  const el = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;\n"
                + "  if (el) el.click(); else { const btns = document.querySelectorAll('button, a, [role=button]'); for (const b of btns) { if (b.textContent.trim() === '" + escaped + "') { b.click(); return; } } }\n"
                + "}";
        page.evaluate(script);
    }

    private void typeViaJs(Page page, String selector, String text) {
        String escaped = escapeJs(selector);
        String escapedText = escapeJs(text);
        String script = looksLikeCss(selector)
                ? "() => { const el = document.querySelector('" + escaped + "'); if (el) { el.value = '" + escapedText + "'; el.dispatchEvent(new Event('input', { bubbles: true })); } }"
                : "() => {\n"
                + "  const inputs = document.querySelectorAll('input, textarea');\n"
                + "  for (const el of inputs) {\n"
                + "    if (el.placeholder && el.placeholder.includes('" + escaped + "')) { el.value = '" + escapedText + "'; el.dispatchEvent(new Event('input', { bubbles: true })); return; }\n"
                + "    const label = el.closest('label') || (el.getAttribute('aria-label') || '');\n"
                + "    if (label && label.includes('" + escaped + "')) { el.value = '" + escapedText + "'; el.dispatchEvent(new Event('input', { bubbles: true })); return; }\n"
                + "  }\n"
                + "  // Fallback: find by preceding text or parent text\n"
                + "  for (const el of inputs) {\n"
                + "    const prev = el.previousElementSibling;\n"
                + "    if (prev && prev.textContent && prev.textContent.includes('" + escaped + "')) { el.value = '" + escapedText + "'; el.dispatchEvent(new Event('input', { bubbles: true })); return; }\n"
                + "  }\n"
                + "}";
        page.evaluate(script);
    }

    public String scroll(String userId, String direction) {
        Page page = getOrCreatePage(userId);
        int delta = "up".equalsIgnoreCase(direction) ? -500 : 500;
        page.evaluate("window.scrollBy(0, " + delta + ")");
        page.waitForTimeout(300);
        return capturePageState(page, "scrolled " + direction);
    }

    public String goBack(String userId) {
        Page page = getOrCreatePage(userId);
        page.goBack(new Page.GoBackOptions().setTimeout(DEFAULT_TIMEOUT_MS));
        waitForPageReady(page);
        return capturePageState(page, "went back");
    }

    // ── Content extraction ──────────────────────────────────────

    public String getContent(String userId) {
        Page page = getOrCreatePage(userId);
        return capturePageState(page, "content requested");
    }

    public String executeJs(String userId, String script) {
        Page page = getOrCreatePage(userId);
        Object result = page.evaluate(script);
        String resultStr = result != null ? result.toString() : "null";
        return "JS execution result: " + resultStr;
    }

    // ── Screenshot ──────────────────────────────────────────────

    public String screenshot(String userId) {
        Page page = getOrCreatePage(userId);
        try {
            byte[] bytes = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.PNG)
                    .setFullPage(false));
            String filename = "browser-" + System.currentTimeMillis() + ".png";
            Path file = SCREENSHOT_DIR.resolve(filename);
            Files.write(file, bytes);

            String title = page.title();
            String url = page.url();
            pendingBrowserImages.add(new BrowserImage(bytes, filename,
                    "Screenshot of " + title + " (" + url + ")"));
            return String.format("""
                    Screenshot saved and will be sent as an image below.
                    File: %s
                    Page title: %s
                    URL: %s
                    """, file.toAbsolutePath(), title, url);
        } catch (Exception e) {
            log.warn("screenshot failed for user={}: {}", userId, e.getMessage());
            return "Screenshot failed: " + e.getMessage();
        }
    }

    // ── Login helpers ────────────────────────────────────────────

    /**
     * Extract QR code image from the current page. Searches for {@code <img>}
     * tags whose src/class/id/alt contain "qr" or "qrcode", or canvas elements
     * rendering a QR code. Returns the image as a base64 data URL so the user
     * can scan it.
     */
    public String getQrCode(String userId) {
        Page page = getOrCreatePage(userId);
        try {
            // Try to extract a QR code <img> element's src (may be a data URL or
            // relative URL that we can convert to a screenshot)
            String qrInfo = page.evaluate("() => {\n"
                    + "  // Search for QR-related img elements\n"
                    + "  const selectors = [\n"
                    + "    'img[class*=\"qr\"]', 'img[class*=\"Qr\"]', 'img[class*=\"QR\"]',\n"
                    + "    'img[id*=\"qr\"]', 'img[id*=\"Qr\"]', 'img[id*=\"QR\"]',\n"
                    + "    'img[src*=\"qr\"]', 'img[src*=\"Qr\"]', 'img[src*=\"QR\"]',\n"
                    + "    'img[src*=\"qrcode\"]', 'img[src*=\"QRCode\"]',\n"
                    + "    'img[alt*=\"二维码\"]', 'img[alt*=\"QR\"]', 'img[alt*=\"qr\"]',\n"
                    + "    '.qrcode img', '.qr-code img', '.login-qr img',\n"
                    + "    '.scan-code img', '[class*=\"qrcode\"] img',\n"
                    + "    'canvas[class*=\"qr\"]', 'canvas[class*=\"Qr\"]', 'canvas[id*=\"qr\"]'\n"
                    + "  ];\n"
                    + "  for (const sel of selectors) {\n"
                    + "    try {\n"
                    + "      const el = document.querySelector(sel);\n"
                    + "      if (el) {\n"
                    + "        const rect = el.getBoundingClientRect();\n"
                    + "        if (rect.width > 50 && rect.height > 50) {\n"
                    + "          return JSON.stringify({\n"
                    + "            tag: el.tagName.toLowerCase(),\n"
                    + "            src: el.src || '',\n"
                    + "            width: Math.round(rect.width),\n"
                    + "            height: Math.round(rect.height),\n"
                    + "            x: Math.round(rect.x),\n"
                    + "            y: Math.round(rect.y)\n"
                    + "          });\n"
                    + "        }\n"
                    + "      }\n"
                    + "    } catch(e) {}\n"
                    + "  }\n"
                    + "  return null;\n"
                    + "}").toString();

            if (qrInfo != null && !qrInfo.isEmpty() && !"null".equals(qrInfo)) {
                String json = qrInfo.toString();
                int x = extractJsonInt(json, "x");
                int y = extractJsonInt(json, "y");
                int w = extractJsonInt(json, "width");
                int h = extractJsonInt(json, "height");

                // Clamp clip area to viewport to avoid "Clipped area is either
                // empty or outside the resulting image" when the QR element is
                // inside a modal with negative/offscreen coordinates.
                int vpW = page.evaluate("() => window.innerWidth").toString().isEmpty()
                        ? 1280 : Integer.parseInt(page.evaluate("() => window.innerWidth").toString());
                int vpH = page.evaluate("() => window.innerHeight").toString().isEmpty()
                        ? 800 : Integer.parseInt(page.evaluate("() => window.innerHeight").toString());
                int cx = Math.max(0, x);
                int cy = Math.max(0, y);
                int cw = Math.min(w, vpW - cx);
                int ch = Math.min(h, vpH - cy);

                if (cw > 50 && ch > 50) {
                    byte[] bytes = page.screenshot(new Page.ScreenshotOptions()
                            .setType(ScreenshotType.PNG)
                            .setClip(cx, cy, cw, ch));
                    pendingBrowserImages.add(new BrowserImage(bytes, "qrcode.png",
                            "QR code (" + w + "x" + h + ")"));
                    return "QR code found (" + w + "x" + h + ") and will be sent as an image below.";
                }
                // Clipped area too small — fall through to full screenshot
                log.debug("QR element at ({},{}) {}x{} but clipped area {}x{} too small, using full screenshot",
                        x, y, w, h, cw, ch);
            }

            // No dedicated QR element found — some sites render QR in iframe or
            // dynamically. Take a full-page screenshot and send as image.
            byte[] bytes = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.PNG).setFullPage(false));
            String title = safeTitle(page);
            String url = safeUrl(page);
            pendingBrowserImages.add(new BrowserImage(bytes, "qrcode-fullpage.png",
                    "Full page screenshot for QR scanning. Title: " + title + ", URL: " + url));
            return "No specific QR code element detected. Full page screenshot will be sent as an image below.\n"
                    + "Title: " + title + "\nURL: " + url;
        } catch (Exception e) {
            log.warn("getQrCode failed for user={}: {}", userId, e.getMessage());
            return "Failed to extract QR code: " + e.getMessage();
        }
    }

    /**
     * Wait for the page to navigate away from its current URL (e.g. after the
     * user scans a QR code and the site redirects). Polls every 2 seconds up to
     * the given timeout. Returns the new page state once the URL changes, or the
     * current state on timeout.
     */
    public String waitForPageChange(String userId, int timeoutSeconds) {
        Page page = getOrCreatePage(userId);
        String originalUrl = safeUrl(page);
        int maxWait = Math.min(timeoutSeconds, 120);
        int pollMs = 2000;
        long deadline = System.currentTimeMillis() + (long) maxWait * 1000;

        log.info("waiting for page change from {} (max {}s)", originalUrl, maxWait);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(pollMs);
            try {
                String currentUrl = safeUrl(page);
                if (!currentUrl.equals(originalUrl) && !currentUrl.contains("login")
                        && !currentUrl.contains("Login") && !currentUrl.contains("signin")) {
                    log.info("page changed: {} -> {}", originalUrl, currentUrl);
                    waitForPageReady(page);
                    return "Page navigated to: " + currentUrl + "\n"
                            + capturePageState(page, "waited for page change after QR scan");
                }
                // Also check if a logged-in indicator appeared on the same URL
                Object loggedIn = page.evaluate("() => {\n"
                        + "  const body = document.body.innerText || '';\n"
                        + "  if (body.length < 50 && body.trim().length === 0) return false;\n"
                        + "  const indicators = ['退出登录', '退出', 'logout', 'Logout', '我的', '个人中心', '账号管理', '设置'];\n"
                        + "  return indicators.some(w => body.includes(w));\n"
                        + "}").toString();
                if ("true".equals(loggedIn)) {
                    log.info("login indicator found on {}", currentUrl);
                    waitForPageReady(page);
                    return "Login successful (logged-in indicator detected).\n"
                            + capturePageState(page, "detected login success");
                }
            } catch (Exception e) {
                log.debug("poll error during waitForPageChange: {}", e.getMessage());
            }
        }
        return "Timed out after " + maxWait + "s waiting for page change. Current state:\n"
                + capturePageState(page, "timeout waiting for page change");
    }

    /**
     * After the user provides a phone number, type it into the page. After the
     * user provides a verification code, type it into the page. These are just
     * convenience wrappers — the caller needs to find the correct selectors first
     * by calling getContent.
     */

    // ── Page management ─────────────────────────────────────────

    private Page getOrCreatePage(String userId) {
        UserContext ctx = contexts.compute(userId, (k, existing) -> {
            if (isUserContextAlive(existing)) {
                existing.lastAccess = System.currentTimeMillis();
                return existing;
            }
            if (existing != null) {
                closeContext(k, existing);
            }
            Path storagePath = getStoragePath(userId);
            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .setViewportSize(1280, 800)
                    .setLocale("zh-CN")
                    .setBypassCSP(true)
                    .setJavaScriptEnabled(true)
                    .setHasTouch(false);
            // Restore cookies + localStorage from previous session
            if (Files.isRegularFile(storagePath)) {
                ctxOpts.setStorageStatePath(storagePath);
                log.info("restored browser storage state for user={} from {}", userId, storagePath.getFileName());
            }
            BrowserContext browserContext = getBrowser().newContext(ctxOpts);
            // Inject stealth scripts to evade bot detection
            browserContext.addInitScript("() => {\n"
                    + "  // Remove webdriver flag\n"
                    + "  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });\n"
                    + "  // Fake chrome runtime\n"
                    + "  window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} };\n"
                    + "  // Fake plugins (PluginArray-like) — use original prototype for instanceof checks\n"
                    + "  const origPlugins = navigator.plugins;\n"
                    + "  const makePlugins = () => {\n"
                    + "    const arr = [\n"
                    + "      { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },\n"
                    + "      { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },\n"
                    + "      { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }\n"
                    + "    ];\n"
                    + "    arr.item = function(i) { return this[i]; };\n"
                    + "    arr.namedItem = function(name) { return this.find(p => p.name === name); };\n"
                    + "    arr.refresh = function() {};\n"
                    + "    Object.setPrototypeOf(arr, Object.getPrototypeOf(origPlugins));\n"
                    + "    return arr;\n"
                    + "  };\n"
                    + "  Object.defineProperty(navigator, 'plugins', { get: makePlugins });\n"
                    + "  // Fake mimeTypes\n"
                    + "  Object.defineProperty(navigator, 'mimeTypes', { get: () => ({ length: 0, item: () => null, namedItem: () => null }) });\n"
                    + "  // Fake languages\n"
                    + "  Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en'] });\n"
                    + "  // Fake hardware info\n"
                    + "  Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });\n"
                    + "  Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });\n"
                    + "  Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });\n"
                    + "  // Fake vendor\n"
                    + "  Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' });\n"
                    + "  Object.defineProperty(navigator, 'vendorSub', { get: () => '' });\n"
                    + "  // Hide automation permissions query\n"
                    + "  const originalQuery = window.navigator.permissions.query;\n"
                    + "  window.navigator.permissions.query = (parameters) => (\n"
                    + "    parameters.name === 'notifications' ?\n"
                    + "      Promise.resolve({ state: Notification.permission }) :\n"
                    + "      originalQuery(parameters)\n"
                    + "  );\n"
                    + "}");
            Page newPage = browserContext.newPage();
            log.info("created new browser context for user={}", userId);
            return new UserContext(browserContext, newPage, System.currentTimeMillis(), storagePath);
        });
        ctx.lastAccess = System.currentTimeMillis();
        return ctx.page;
    }

    private boolean isUserContextAlive(UserContext ctx) {
        if (ctx == null || !isBrowserAlive(browser)) {
            return false;
        }
        try {
            return ctx.page != null && !ctx.page.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private void discardUserContext(String userId) {
        UserContext removed = contexts.remove(userId);
        if (removed != null) {
            closeContext(userId, removed);
        }
    }

    private static boolean isTargetClosedError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            String name = current.getClass().getName();
            if ((message != null && message.contains("Target page, context or browser has been closed"))
                    || (message != null && message.contains("TargetClosedError"))
                    || name.contains("TargetClosedError")) {
                return true;
            }
        }
        return false;
    }

    private void saveAllStates() {
        contexts.forEach((userId, ctx) -> {
            try {
                ctx.context.storageState(new BrowserContext.StorageStateOptions()
                        .setPath(ctx.storagePath));
            } catch (Exception e) {
                log.debug("periodic save failed for user={}: {}", userId, e.getMessage());
            }
        });
    }

    private void closeContext(String userId, UserContext ctx) {
        try {
            // Persist cookies + localStorage before closing
            ctx.context.storageState(new BrowserContext.StorageStateOptions()
                    .setPath(ctx.storagePath));
            log.info("saved browser storage state for user={} to {}", userId, ctx.storagePath.getFileName());
        } catch (Exception e) {
            log.warn("failed to save browser storage state for user={}: {}", userId, e.getMessage());
        }
        try {
            ctx.page.close();
            ctx.context.close();
            log.info("closed browser context for user={}", userId);
        } catch (Exception e) {
            log.warn("error closing browser context for user={}: {}", userId, e.getMessage());
        }
    }

    private void waitForPageReady(Page page) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_READY_TIMEOUT_MS));
        } catch (TimeoutError e) {
            log.debug("browser page DOMContentLoaded wait timed out: {}", e.getMessage());
        }
        page.waitForTimeout(500);
    }

    /**
     * Extract the current page state as structured text for the LLM.
     */
    private String capturePageState(Page page, String action) {
        String title = safeTitle(page);
        String url = safeUrl(page);
        String bodyText = safeEvaluateString(page, "() => document.body ? document.body.innerText : ''");
        int maxChars = 4000;
        if (bodyText.length() > maxChars) {
            bodyText = bodyText.substring(0, maxChars) + "... (truncated)";
        }

        // Extract interactive elements
        String interactiveElements = safeEvaluateString(page, "() => {\n"
                + "  const els = [];\n"
                + "  document.querySelectorAll('a, button, input, select, textarea, [role=\"button\"], [role=\"link\"]').forEach(el => {\n"
                + "    const tag = el.tagName.toLowerCase();\n"
                + "    const text = (el.textContent || el.value || el.placeholder || el.getAttribute('aria-label') || '').trim().substring(0, 80);\n"
                + "    const id = el.id ? '#' + el.id : '';\n"
                + "    const cls = el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : '';\n"
                + "    const href = el.getAttribute('href') || '';\n"
                + "    if (text || href) els.push(tag + id + cls + ': ' + (text || href));\n"
                + "  });\n"
                + "  return els.slice(0, 30).join('\\n');\n"
                + "}").toString();

        return String.format("""
                Action: %s
                Title: %s
                URL: %s

                Interactive elements:
                %s

                Page content:
                %s
                """, action, title, url,
                interactiveElements.isEmpty() ? "(none detected)" : interactiveElements,
                bodyText);
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "(title unavailable: " + e.getMessage() + ")";
        }
    }

    private static String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "(url unavailable: " + e.getMessage() + ")";
        }
    }

    /**
     * Escape a string for safe embedding in a JS single-quoted string literal.
     */
    private static int extractJsonInt(String json, String key) {
        // Simple extraction without a full JSON parser — the JS side returns
        // controlled output like {"x":100,"y":200}
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        idx += search.length();
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == idx) return 0;
        return Integer.parseInt(json.substring(idx, end));
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String safeEvaluateString(Page page, String script) {
        try {
            Object value = page.evaluate(script);
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            return "(page content unavailable: " + e.getMessage() + ")";
        }
    }

    private Path getStoragePath(String userId) {
        // Sanitize userId to a safe filename
        String safe = userId.replaceAll("[^a-zA-Z0-9_\\-.@]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return STORAGE_DIR.resolve(safe + ".json");
    }

    private static class UserContext {
        final BrowserContext context;
        final Page page;
        final Path storagePath;
        volatile long lastAccess;

        UserContext(BrowserContext context, Page page, long lastAccess, Path storagePath) {
            this.context = context;
            this.page = page;
            this.lastAccess = lastAccess;
            this.storagePath = storagePath;
        }
    }
}
