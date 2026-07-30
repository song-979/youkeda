package com.youkeda.project.wechatproject.bot.tool.browser;

/**
 * Exception thrown when a browser MCP operation fails.
 */
public class BrowserMcpException extends RuntimeException {

    private final String code;

    public BrowserMcpException(String code, String message) {
        super("Browser MCP error [" + code + "]: " + message);
        this.code = code;
    }

    public BrowserMcpException(String message) {
        super(message);
        this.code = "UNKNOWN";
    }

    public String getCode() { return code; }
}
