package com.youkeda.project.wechatproject.bot.context;

import java.util.List;

/** DAG-backed task state supplied to planning, reflection, resume, or one isolated sub-agent. */
public record ContextTaskState(
        String dagId,
        String dagStatus,
        int revision,
        String currentNodeId,
        String latestUserInput,
        String summary,
        List<ContextTaskRecord> records) {

    public ContextTaskState {
        records = records != null ? List.copyOf(records) : List.of();
    }

    public boolean isEmpty() {
        return (dagId == null || dagId.isBlank())
                && (latestUserInput == null || latestUserInput.isBlank())
                && (summary == null || summary.isBlank())
                && records.isEmpty();
    }

    public static ContextTaskState empty() {
        return new ContextTaskState(null, null, 0, null, null, null, List.of());
    }
}
