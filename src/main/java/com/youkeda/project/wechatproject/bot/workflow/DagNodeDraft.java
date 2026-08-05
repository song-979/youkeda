package com.youkeda.project.wechatproject.bot.workflow;

import java.util.List;
import java.util.Map;

/** Minimal semantic task proposed by the planner. Runtime policy is added by the backend. */
public record DagNodeDraft(
        String key,
        String agentType,
        String instruction,
        String contextNote,
        List<String> dependsOn,
        Map<String, Object> parameters) {

    public DagNodeDraft {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }
}
