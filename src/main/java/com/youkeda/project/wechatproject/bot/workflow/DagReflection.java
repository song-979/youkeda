package com.youkeda.project.wechatproject.bot.workflow;

import java.util.List;

public record DagReflection(
        Action action,
        String targetNode,
        String reason,
        String question,
        String finalReply,
        List<DagNodeDraft> newNodes) {

    public enum Action {
        CONTINUE,
        APPEND,
        RETRY,
        ROLLBACK,
        ASK_USER,
        COMPLETE,
        PARTIAL_COMPLETE,
        INVALID
    }

    public DagReflection {
        action = action != null ? action : Action.INVALID;
        newNodes = newNodes != null ? List.copyOf(newNodes) : List.of();
    }

    public static DagReflection invalid(String reason) {
        return new DagReflection(Action.INVALID, null, reason, null, null, List.of());
    }
}
