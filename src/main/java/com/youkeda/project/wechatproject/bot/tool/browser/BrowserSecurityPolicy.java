package com.youkeda.project.wechatproject.bot.tool.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Security guard for browser operations:
 * - URL protocol validation (block dangerous protocols)
 * - Internal/private IP blocking (SSRF protection)
 * - URL allowlist/blocklist matching
 * - Script safety validation
 * - Login request throttling
 */
public class BrowserSecurityPolicy {

    private static final Logger log = LoggerFactory.getLogger(BrowserSecurityPolicy.class);

    private static final Set<String> BLOCKED_PROTOCOLS = Set.of(
            "file", "chrome", "chrome-extension", "about", "data", "javascript", "blob", "ftp"
    );

    // Script security: dangerous glob patterns
    private static final List<String> DANGEROUS_SCRIPT_PATTERNS = List.of(
            "fetch(", "XMLHttpRequest",
            "localStorage", "sessionStorage",
            "document.cookie",
            "window.open", "location=", "window.location",
            "WebSocket", "EventSource",
            "navigator.sendBeacon",
            "form.submit", ".submit(",
            "document.write",
            "eval(",
            "Function(",
            "setTimeout(", "setInterval("
    );

    // Max human login requests per session
    private static final int MAX_LOGIN_REQUESTS = 3;
    private static final int MAX_RICH_TEXT_CHARS = 200_000;
    private static final int MAX_SELECTOR_CHARS = 500;

    private final BrowserMcpProperties properties;
    private int loginRequestCount = 0;

    public BrowserSecurityPolicy(BrowserMcpProperties properties) {
        this.properties = properties;
    }

    /**
     * Validate a URL before navigation. Throws BrowserSecurityException if blocked.
     */
    public void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BrowserSecurityException("URL must not be empty");
        }

        URI uri;
        try {
            // Java URI requires a scheme; auto-prepend https if missing
            String normalized = url.trim();
            if (!normalized.contains("://")) {
                normalized = "https://" + normalized;
            }
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new BrowserSecurityException("Invalid URL format: " + url);
        }

        // 1. Protocol check
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new BrowserSecurityException("URL must have a protocol");
        }
        if (BLOCKED_PROTOCOLS.contains(scheme.toLowerCase())) {
            throw new BrowserSecurityException("Protocol '" + scheme + "' is not allowed");
        }

        // 2. Host check
        String host = uri.getHost();
        if (host == null) {
            throw new BrowserSecurityException("URL must have a valid host");
        }

        // 3. Internal IP check (SSRF protection)
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                throw new BrowserSecurityException("Access to internal/private IP addresses is prohibited: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            // Host doesn't resolve yet — allow, the browser will handle the error
        }

        // 4. Blocklist check
        List<String> blocklist = properties.getUrlBlocklist();
        if (blocklist != null && !blocklist.isEmpty()) {
            for (String pattern : blocklist) {
                if (matchGlob(host, pattern)) {
                    throw new BrowserSecurityException("URL host '" + host + "' is in the blocklist");
                }
            }
        }

        // 5. Allowlist check
        List<String> allowlist = properties.getUrlAllowlist();
        if (allowlist != null && !allowlist.isEmpty()) {
            boolean allowed = false;
            for (String pattern : allowlist) {
                if (matchGlob(host, pattern)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new BrowserSecurityException("URL host '" + host + "' is not in the allowlist");
            }
        }
    }

    /**
     * Validate a script for evaluate_script calls. Throws BrowserSecurityException if dangerous patterns detected.
     */
    public void validateScript(String script) {
        if (script == null || script.isBlank()) {
            throw new BrowserSecurityException("Script must not be empty");
        }

        if (!properties.isScriptEvaluationEnabled()) {
            throw new BrowserSecurityException("Script evaluation is disabled. Enable agent.tools.browser.script-evaluation-enabled to use this feature.");
        }

        String lowerScript = script.toLowerCase();
        for (String dangerous : DANGEROUS_SCRIPT_PATTERNS) {
            if (lowerScript.contains(dangerous.toLowerCase())) {
                throw new BrowserSecurityException("Script contains dangerous operation: " + dangerous
                        + ". Only read-only DOM operations (querySelector, innerText, textContent, getAttribute) are allowed.");
            }
        }
    }

    /**
     * Validate controlled rich-text writes. This does not allow arbitrary user scripts;
     * BrowserTools generates the script from plain text and an optional CSS selector.
     */
    public void validateRichTextWrite(String text, String selector) {
        if (!properties.isRichTextWriteEnabled()) {
            throw new BrowserSecurityException("Rich text writing is disabled. Enable agent.tools.browser.rich-text-write-enabled to use this feature.");
        }
        if (text == null) {
            throw new BrowserSecurityException("Rich text content must not be null");
        }
        if (text.length() > MAX_RICH_TEXT_CHARS) {
            throw new BrowserSecurityException("Rich text content is too large: " + text.length()
                    + " chars. Maximum is " + MAX_RICH_TEXT_CHARS + ".");
        }
        if (selector != null && selector.length() > MAX_SELECTOR_CHARS) {
            throw new BrowserSecurityException("CSS selector is too long. Maximum is " + MAX_SELECTOR_CHARS + " chars.");
        }
    }

    /**
     * Check and consume a human login request. Throws BrowserSecurityException if limit exceeded.
     */
    public void checkLoginRequest() {
        loginRequestCount++;
        if (loginRequestCount > MAX_LOGIN_REQUESTS) {
            throw new BrowserSecurityException("Too many login requests (" + loginRequestCount
                    + "). Maximum is " + MAX_LOGIN_REQUESTS + " per session.");
        }
    }

    /**
     * Simple glob matching: "*" matches any sequence, "?" matches any single character.
     */
    static boolean matchGlob(String text, String pattern) {
        String regex = Pattern.quote(pattern)
                .replace("\\*", ".*")
                .replace("\\?", ".");
        return text.matches(regex);
    }
}
