package com.youkeda.project.wechatproject.bot.context;

import java.util.Map;

/** Transfers one completed tool-loop archive reference to its owning AgentResult. */
public final class ToolLoopContextRuntime {

    private static final ThreadLocal<Report> COMPLETED = new ThreadLocal<>();

    private ToolLoopContextRuntime() {
    }

    static void publish(Report report) {
        if (report != null) {
            COMPLETED.set(report);
        }
    }

    public static Report drain() {
        Report report = COMPLETED.get();
        COMPLETED.remove();
        return report;
    }

    public record Report(String sessionId, String initialContextReference) {

        public Map<String, String> signals() {
            if (sessionId == null || sessionId.isBlank()) {
                return Map.of();
            }
            return Map.of(
                    "tool_transcript_session", sessionId,
                    "tool_initial_context_reference", initialContextReference != null
                            ? initialContextReference : "tool-transcript://" + sessionId + "/context-initial");
        }

        public Map<String, Object> resumeState() {
            return Map.copyOf(signals());
        }
    }
}
