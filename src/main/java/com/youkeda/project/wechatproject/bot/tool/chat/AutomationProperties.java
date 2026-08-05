package com.youkeda.project.wechatproject.bot.tool.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools.automation")
public class AutomationProperties {

    private boolean enabled = true;
    private String defaultRecipientId;
    private String storagePath = "data/tool-automation";
    private String timeZone = "Asia/Shanghai";
    private int schedulerPoolSize = 2;
    private int maxSendAttempts = 3;
    private boolean sendMissedRemindersOnStartup = false;
    private boolean heartbeatEnabled = true;
    private int heartbeatWatchdogMinutes = 60;
    private int heartbeatFallbackMinutes = 60;
    private int heartbeatMinIntervalMinutes = 10;
    private int heartbeatMaxIntervalHours = 24;
    private int heartbeatBusyDeferralMinutes = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultRecipientId() {
        return defaultRecipientId;
    }

    public void setDefaultRecipientId(String defaultRecipientId) {
        this.defaultRecipientId = defaultRecipientId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public void setSchedulerPoolSize(int schedulerPoolSize) {
        this.schedulerPoolSize = schedulerPoolSize;
    }

    public int getMaxSendAttempts() {
        return maxSendAttempts;
    }

    public void setMaxSendAttempts(int maxSendAttempts) {
        this.maxSendAttempts = maxSendAttempts;
    }

    public boolean isSendMissedRemindersOnStartup() {
        return sendMissedRemindersOnStartup;
    }

    public void setSendMissedRemindersOnStartup(boolean sendMissedRemindersOnStartup) {
        this.sendMissedRemindersOnStartup = sendMissedRemindersOnStartup;
    }

    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
    }

    public int getHeartbeatWatchdogMinutes() {
        return heartbeatWatchdogMinutes;
    }

    public void setHeartbeatWatchdogMinutes(int heartbeatWatchdogMinutes) {
        this.heartbeatWatchdogMinutes = heartbeatWatchdogMinutes;
    }

    public int getHeartbeatFallbackMinutes() {
        return heartbeatFallbackMinutes;
    }

    public void setHeartbeatFallbackMinutes(int heartbeatFallbackMinutes) {
        this.heartbeatFallbackMinutes = heartbeatFallbackMinutes;
    }

    public int getHeartbeatMinIntervalMinutes() {
        return heartbeatMinIntervalMinutes;
    }

    public void setHeartbeatMinIntervalMinutes(int heartbeatMinIntervalMinutes) {
        this.heartbeatMinIntervalMinutes = heartbeatMinIntervalMinutes;
    }

    public int getHeartbeatMaxIntervalHours() {
        return heartbeatMaxIntervalHours;
    }

    public void setHeartbeatMaxIntervalHours(int heartbeatMaxIntervalHours) {
        this.heartbeatMaxIntervalHours = heartbeatMaxIntervalHours;
    }

    public int getHeartbeatBusyDeferralMinutes() {
        return heartbeatBusyDeferralMinutes;
    }

    public void setHeartbeatBusyDeferralMinutes(int heartbeatBusyDeferralMinutes) {
        this.heartbeatBusyDeferralMinutes = heartbeatBusyDeferralMinutes;
    }
}
