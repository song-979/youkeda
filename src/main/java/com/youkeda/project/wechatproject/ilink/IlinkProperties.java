package com.youkeda.project.wechatproject.ilink;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * iLink 接入层配置。
 * 在 application.properties / yml 中以 {@code ilink.*} 前缀覆盖。
 */
@ConfigurationProperties(prefix = "ilink")
public class IlinkProperties {

    /** 是否启用接入层 */
    private boolean enabled = true;

    /** 应用启动后是否自动发起登录 */
    private boolean autoLogin = true;

    /** 登录超时（毫秒） */
    private long loginTimeoutMs = 180_000;

    /** 心跳间隔（毫秒） */
    private long heartbeatIntervalMs = 5000;

    /** 是否启用心跳 */
    private boolean heartbeatEnabled = true;

    /** 连接超时（毫秒） */
    private long connectTimeoutMs = 35_000;

    /** 读取超时（毫秒） */
    private long readTimeoutMs = 35_000;

    /** 写入超时（毫秒） */
    private long writeTimeoutMs = 35_000;

    /** HTTP 最大重试次数 */
    private int httpMaxRetries = 3;

    // ---- getters / setters ----

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoLogin() { return autoLogin; }
    public void setAutoLogin(boolean autoLogin) { this.autoLogin = autoLogin; }

    public long getLoginTimeoutMs() { return loginTimeoutMs; }
    public void setLoginTimeoutMs(long loginTimeoutMs) { this.loginTimeoutMs = loginTimeoutMs; }

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public boolean isHeartbeatEnabled() { return heartbeatEnabled; }
    public void setHeartbeatEnabled(boolean heartbeatEnabled) { this.heartbeatEnabled = heartbeatEnabled; }

    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public long getWriteTimeoutMs() { return writeTimeoutMs; }
    public void setWriteTimeoutMs(long writeTimeoutMs) { this.writeTimeoutMs = writeTimeoutMs; }

    public int getHttpMaxRetries() { return httpMaxRetries; }
    public void setHttpMaxRetries(int httpMaxRetries) { this.httpMaxRetries = httpMaxRetries; }
}
