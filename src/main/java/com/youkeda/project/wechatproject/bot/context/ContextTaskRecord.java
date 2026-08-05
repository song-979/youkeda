package com.youkeda.project.wechatproject.bot.context;

import java.util.Map;
import java.util.List;

/** A compact, model-facing projection of one DAG node. */
public record ContextTaskRecord(
        String id,
        String key,
        String agentType,
        String status,
        List<String> dependsOn,
        String instruction,
        String result,
        String error,
        String messageToUser,
        Map<String, String> signals) {

    public ContextTaskRecord {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        signals = signals != null ? Map.copyOf(signals) : Map.of();
    }
}
