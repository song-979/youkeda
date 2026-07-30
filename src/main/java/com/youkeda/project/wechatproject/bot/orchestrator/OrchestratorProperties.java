package com.youkeda.project.wechatproject.bot.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.orchestrator")
public class OrchestratorProperties {
    private int maxLoops = 5;
    private boolean clarificationEnabled = true;
    private boolean reflectionEnabled = true;

    public int getMaxLoops() { return maxLoops; }
    public void setMaxLoops(int maxLoops) { this.maxLoops = maxLoops; }

    public boolean isClarificationEnabled() { return clarificationEnabled; }
    public void setClarificationEnabled(boolean clarificationEnabled) { this.clarificationEnabled = clarificationEnabled; }

    public boolean isReflectionEnabled() { return reflectionEnabled; }
    public void setReflectionEnabled(boolean reflectionEnabled) { this.reflectionEnabled = reflectionEnabled; }
}
