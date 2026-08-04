package com.youkeda.project.wechatproject.bot.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Backend limits and persistence settings for the DAG runtime. */
@ConfigurationProperties(prefix = "agent.orchestrator")
public class DagOrchestrationProperties {
    private int maxExecutionSeconds = 300;
    private boolean reflectionEnabled = true;
    private String dagStorePath = "data/orchestration/workflows.db";
    private int dagMaxNodes = 32;
    private int dagMaxWaves = 16;
    private int dagMaxReplans = 8;
    private int dagPlanRepairAttempts = 1;
    private int dagDefaultMaxAttempts = 3;
    private int dagNodeTimeoutSeconds = 180;

    public int getMaxExecutionSeconds() { return maxExecutionSeconds; }
    public void setMaxExecutionSeconds(int value) { this.maxExecutionSeconds = value; }

    public boolean isReflectionEnabled() { return reflectionEnabled; }
    public void setReflectionEnabled(boolean value) { this.reflectionEnabled = value; }

    public String getDagStorePath() { return dagStorePath; }
    public void setDagStorePath(String value) { this.dagStorePath = value; }

    public int getDagMaxNodes() { return dagMaxNodes; }
    public void setDagMaxNodes(int value) { this.dagMaxNodes = value; }

    public int getDagMaxWaves() { return dagMaxWaves; }
    public void setDagMaxWaves(int value) { this.dagMaxWaves = value; }

    public int getDagMaxReplans() { return dagMaxReplans; }
    public void setDagMaxReplans(int value) { this.dagMaxReplans = value; }

    public int getDagPlanRepairAttempts() { return dagPlanRepairAttempts; }
    public void setDagPlanRepairAttempts(int value) { this.dagPlanRepairAttempts = value; }

    public int getDagDefaultMaxAttempts() { return dagDefaultMaxAttempts; }
    public void setDagDefaultMaxAttempts(int value) { this.dagDefaultMaxAttempts = value; }

    public int getDagNodeTimeoutSeconds() { return dagNodeTimeoutSeconds; }
    public void setDagNodeTimeoutSeconds(int value) { this.dagNodeTimeoutSeconds = value; }
}
