package com.youkeda.project.wechatproject.bot.tool;

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
}
