package com.youkeda.project.wechatproject.bot.tool.browser;

/**
 * Exception thrown when a browser operation is blocked by security policy.
 */
public class BrowserSecurityException extends RuntimeException {

    public BrowserSecurityException(String message) {
        super(message);
    }
}
