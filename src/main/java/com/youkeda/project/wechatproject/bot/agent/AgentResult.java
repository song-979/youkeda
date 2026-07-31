package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.model.ModelReply;

import java.util.List;
import java.util.Map;

public class AgentResult {

    public enum Status { SUCCESS, FAILED, PAUSED }

    /**
     * Structured error classification so the harness does not have to guess
     * error kinds from free-text messages.
     */
    public enum ErrorKind {
        NONE,
        /** LLM/API rate limit or quota exhaustion */
        RATE_LIMIT,
        /** Network timeout or read timeout */
        TIMEOUT,
        /** Upstream API error (5xx, provider error) */
        UPSTREAM,
        /** Auth failure, expired token, invalid api key */
        AUTH,
        /** Tool execution failure */
        TOOL,
        /** Input validation failure */
        VALIDATION,
        /** Anything not classified above */
        UNKNOWN
    }

    private final String taskId;
    private final Status status;
    private final Object output;
    private final String rawOutput;
    private final String errorMessage;
    private final ErrorKind errorKind;
    private final String messageToUser;
    private final Map<String, Object> resumeState;
    private final List<ModelReply.ImagePayload> pausedImages;

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, String messageToUser, Map<String, Object> resumeState,
                       List<ModelReply.ImagePayload> pausedImages) {
        this(taskId, status, output, rawOutput, errorMessage, classify(errorMessage), messageToUser,
                resumeState, pausedImages);
    }

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, ErrorKind errorKind, String messageToUser,
                       Map<String, Object> resumeState, List<ModelReply.ImagePayload> pausedImages) {
        this.taskId = taskId;
        this.status = status;
        this.output = output;
        this.rawOutput = rawOutput;
        this.errorMessage = errorMessage;
        this.errorKind = errorKind != null ? errorKind : ErrorKind.NONE;
        this.messageToUser = messageToUser;
        this.resumeState = resumeState != null ? Map.copyOf(resumeState) : Map.of();
        this.pausedImages = pausedImages != null ? List.copyOf(pausedImages) : List.of();
    }

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, String messageToUser, Map<String, Object> resumeState) {
        this(taskId, status, output, rawOutput, errorMessage, messageToUser, resumeState, List.of());
    }

    public AgentResult(String taskId, Status status, Object output, String rawOutput, String errorMessage) {
        this(taskId, status, output, rawOutput, errorMessage, null, Map.of());
    }

    public String taskId() { return taskId; }
    public Status status() { return status; }
    public Object output() { return output; }
    public String rawOutput() { return rawOutput; }
    public String errorMessage() { return errorMessage; }
    public ErrorKind errorKind() { return errorKind; }
    public String messageToUser() { return messageToUser; }
    public Map<String, Object> resumeState() { return resumeState; }
    public List<ModelReply.ImagePayload> pausedImages() { return pausedImages; }

    public boolean isPaused() { return status == Status.PAUSED; }

    public static AgentResult success(String taskId, Object output, String rawOutput) {
        return new AgentResult(taskId, Status.SUCCESS, output, rawOutput, null);
    }

    public static AgentResult failed(String taskId, String errorMessage) {
        return new AgentResult(taskId, Status.FAILED, null, null, errorMessage);
    }

    public static AgentResult failed(String taskId, String errorMessage, ErrorKind errorKind) {
        return new AgentResult(taskId, Status.FAILED, null, null, errorMessage, errorKind, null,
                Map.of(), List.of());
    }

    /**
     * Best-effort classification of a free-text error message into an {@link ErrorKind}.
     * Used as a bridge so existing call sites gradually move to explicit error kinds.
     */
    public static ErrorKind classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return ErrorKind.NONE;
        }
        String m = errorMessage.toLowerCase();
        if (m.contains("rate limit") || m.contains("rate_limit") || m.contains("too many requests")
                || m.contains("429") || m.contains("限流") || m.contains("quota")) {
            return ErrorKind.RATE_LIMIT;
        }
        if (m.contains("timeout") || m.contains("timed out") || m.contains("超时")) {
            return ErrorKind.TIMEOUT;
        }
        if (m.contains("401") || m.contains("403") || m.contains("unauthorized") || m.contains("forbidden")
                || m.contains("api key") || m.contains("apikey") || m.contains("token expired")
                || m.contains("context token") || m.contains("鉴权") || m.contains("认证")) {
            return ErrorKind.AUTH;
        }
        if (m.contains("validation") || m.contains("invalid argument") || m.contains("参数")) {
            return ErrorKind.VALIDATION;
        }
        if (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("upstream")
                || m.contains("server error") || m.contains("bad gateway")) {
            return ErrorKind.UPSTREAM;
        }
        return ErrorKind.UNKNOWN;
    }

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, List.of());
    }

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState,
                                      List<ModelReply.ImagePayload> pausedImages) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, pausedImages);
    }
}
