package com.youkeda.project.wechatproject.bot.tool.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "agent.tools.browser")
public class BrowserMcpProperties {

    private boolean enabled = false;
    private String nodePath = "node";
    private String mcpEntryPoint = "chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js";
    private boolean headless = true;
    private String userDataDir;
    private Duration callTimeout = Duration.ofSeconds(30);
    private Duration processStartTimeout = Duration.ofSeconds(15);
    private int maxPages = 5;
    private List<String> urlAllowlist;
    private List<String> urlBlocklist;
    private boolean scriptEvaluationEnabled = false;
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration startupHealthCheckTimeout = Duration.ofSeconds(3);
    private Duration crashCooldown = Duration.ofSeconds(30);
    private int maxConsecutiveStartupFailures = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getNodePath() { return nodePath; }
    public void setNodePath(String nodePath) { this.nodePath = nodePath; }

    public String getMcpEntryPoint() { return mcpEntryPoint; }
    public void setMcpEntryPoint(String mcpEntryPoint) { this.mcpEntryPoint = mcpEntryPoint; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }

    public String getUserDataDir() { return userDataDir; }
    public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }

    public Duration getCallTimeout() { return callTimeout; }
    public void setCallTimeout(Duration callTimeout) { this.callTimeout = callTimeout; }

    public Duration getProcessStartTimeout() { return processStartTimeout; }
    public void setProcessStartTimeout(Duration processStartTimeout) { this.processStartTimeout = processStartTimeout; }

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }

    public List<String> getUrlAllowlist() { return urlAllowlist; }
    public void setUrlAllowlist(List<String> urlAllowlist) { this.urlAllowlist = urlAllowlist; }

    public List<String> getUrlBlocklist() { return urlBlocklist; }
    public void setUrlBlocklist(List<String> urlBlocklist) { this.urlBlocklist = urlBlocklist; }

    public boolean isScriptEvaluationEnabled() { return scriptEvaluationEnabled; }
    public void setScriptEvaluationEnabled(boolean scriptEvaluationEnabled) { this.scriptEvaluationEnabled = scriptEvaluationEnabled; }

    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }

    public Duration getStartupHealthCheckTimeout() { return startupHealthCheckTimeout; }
    public void setStartupHealthCheckTimeout(Duration startupHealthCheckTimeout) { this.startupHealthCheckTimeout = startupHealthCheckTimeout; }

    public Duration getCrashCooldown() { return crashCooldown; }
    public void setCrashCooldown(Duration crashCooldown) { this.crashCooldown = crashCooldown; }

    public int getMaxConsecutiveStartupFailures() { return maxConsecutiveStartupFailures; }
    public void setMaxConsecutiveStartupFailures(int maxConsecutiveStartupFailures) { this.maxConsecutiveStartupFailures = maxConsecutiveStartupFailures; }
}
