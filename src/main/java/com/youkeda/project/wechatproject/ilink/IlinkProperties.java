package com.youkeda.project.wechatproject.ilink;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ilink")
public class IlinkProperties {

    private boolean enabled;
    private boolean autoLogin;
    private long loginTimeoutMs;
    private long heartbeatIntervalMs;
    private boolean heartbeatEnabled;
    private long connectTimeoutMs;
    private long readTimeoutMs;
    private long writeTimeoutMs;
    private int httpMaxRetries;

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
