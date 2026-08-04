package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.tools.xiaohongshu")
public class XiaohongshuProperties {

    private boolean enabled = true;
    private String endpoint = "http://127.0.0.1:18060/mcp";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private boolean dryRun = true;
    private int maxImageCount = 18;
    private String publishImageToolName = "publish_content";
    private String publishVideoToolName = "publish_with_video";
    private String detailToolName = "get_feed_detail";
    private String searchToolName = "search_feeds";
    private String loginStatusToolName = "check_login_status";
    private String loginQrcodeToolName = "get_login_qrcode";
    private String postCommentToolName = "post_comment_to_feed";
    private String replyCommentToolName = "reply_comment_in_feed";
    private String userProfileToolName = "user_profile";
    private String likeToolName = "like_feed";
    private String favoriteToolName = "favorite_feed";
    private ManagedProcessProperties process = new ManagedProcessProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getMaxImageCount() {
        return maxImageCount;
    }

    public void setMaxImageCount(int maxImageCount) {
        this.maxImageCount = maxImageCount;
    }

    public String getPublishImageToolName() {
        return publishImageToolName;
    }

    public void setPublishImageToolName(String publishImageToolName) {
        this.publishImageToolName = publishImageToolName;
    }

    public String getPublishVideoToolName() {
        return publishVideoToolName;
    }

    public void setPublishVideoToolName(String publishVideoToolName) {
        this.publishVideoToolName = publishVideoToolName;
    }

    public String getDetailToolName() {
        return detailToolName;
    }

    public void setDetailToolName(String detailToolName) {
        this.detailToolName = detailToolName;
    }

    public String getSearchToolName() {
        return searchToolName;
    }

    public void setSearchToolName(String searchToolName) {
        this.searchToolName = searchToolName;
    }

    public String getLoginStatusToolName() {
        return loginStatusToolName;
    }

    public void setLoginStatusToolName(String loginStatusToolName) {
        this.loginStatusToolName = loginStatusToolName;
    }

    public String getLoginQrcodeToolName() {
        return loginQrcodeToolName;
    }

    public void setLoginQrcodeToolName(String loginQrcodeToolName) {
        this.loginQrcodeToolName = loginQrcodeToolName;
    }

    public String getPostCommentToolName() {
        return postCommentToolName;
    }

    public void setPostCommentToolName(String postCommentToolName) {
        this.postCommentToolName = postCommentToolName;
    }

    public String getReplyCommentToolName() {
        return replyCommentToolName;
    }

    public void setReplyCommentToolName(String replyCommentToolName) {
        this.replyCommentToolName = replyCommentToolName;
    }

    public String getUserProfileToolName() {
        return userProfileToolName;
    }

    public void setUserProfileToolName(String userProfileToolName) {
        this.userProfileToolName = userProfileToolName;
    }

    public String getLikeToolName() {
        return likeToolName;
    }

    public void setLikeToolName(String likeToolName) {
        this.likeToolName = likeToolName;
    }

    public String getFavoriteToolName() {
        return favoriteToolName;
    }

    public void setFavoriteToolName(String favoriteToolName) {
        this.favoriteToolName = favoriteToolName;
    }

    public ManagedProcessProperties getProcess() {
        return process;
    }

    public void setProcess(ManagedProcessProperties process) {
        this.process = process != null ? process : new ManagedProcessProperties();
    }

    public static class ManagedProcessProperties {
        private boolean autoStart = true;
        private List<String> command = new ArrayList<>(List.of("auto"));
        private String workingDirectory = "";
        private Map<String, String> environment = new LinkedHashMap<>();
        private int startupTimeoutMs = 30000;
        private int stopTimeoutMs = 5000;
        private int probeTimeoutMs = 1000;
        private boolean redirectOutput = true;
        private boolean failOnStartupError = false;

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(List<String> command) {
            this.command = command != null ? command : new ArrayList<>();
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> environment) {
            this.environment = environment != null ? environment : new LinkedHashMap<>();
        }

        public int getStartupTimeoutMs() {
            return startupTimeoutMs;
        }

        public void setStartupTimeoutMs(int startupTimeoutMs) {
            this.startupTimeoutMs = startupTimeoutMs;
        }

        public int getStopTimeoutMs() {
            return stopTimeoutMs;
        }

        public void setStopTimeoutMs(int stopTimeoutMs) {
            this.stopTimeoutMs = stopTimeoutMs;
        }

        public int getProbeTimeoutMs() {
            return probeTimeoutMs;
        }

        public void setProbeTimeoutMs(int probeTimeoutMs) {
            this.probeTimeoutMs = probeTimeoutMs;
        }

        public boolean isRedirectOutput() {
            return redirectOutput;
        }

        public void setRedirectOutput(boolean redirectOutput) {
            this.redirectOutput = redirectOutput;
        }

        public boolean isFailOnStartupError() {
            return failOnStartupError;
        }

        public void setFailOnStartupError(boolean failOnStartupError) {
            this.failOnStartupError = failOnStartupError;
        }
    }
}
