package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.artifact.ArtifactRef;
import com.youkeda.project.wechatproject.bot.model.ModelReply;

import java.util.List;
import java.util.Map;

public class AgentResult {

    public enum Status { SUCCESS, FAILED, PAUSED }

    public enum ErrorKind {
        NONE, RATE_LIMIT, TIMEOUT, UPSTREAM, AUTH, TOOL, VALIDATION, UNKNOWN
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
    private final Map<String, String> signals;
    private final List<ArtifactRef> artifacts;

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, String messageToUser, Map<String, Object> resumeState,
                       List<ModelReply.ImagePayload> pausedImages,
                       Map<String, String> signals) {
        this(taskId, status, output, rawOutput, errorMessage, classify(errorMessage), messageToUser,
                resumeState, pausedImages, signals, List.of());
    }

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, ErrorKind errorKind, String messageToUser,
                       Map<String, Object> resumeState, List<ModelReply.ImagePayload> pausedImages,
                       Map<String, String> signals) {
        this(taskId, status, output, rawOutput, errorMessage, errorKind, messageToUser,
                resumeState, pausedImages, signals, List.of());
    }

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, ErrorKind errorKind, String messageToUser,
                       Map<String, Object> resumeState, List<ModelReply.ImagePayload> pausedImages,
                       Map<String, String> signals, List<ArtifactRef> artifacts) {
        this.taskId = taskId;
        this.status = status;
        this.output = output;
        this.rawOutput = rawOutput;
        this.errorMessage = errorMessage;
        this.errorKind = errorKind != null ? errorKind : ErrorKind.NONE;
        this.messageToUser = messageToUser;
        this.resumeState = resumeState != null ? Map.copyOf(resumeState) : Map.of();
        this.pausedImages = pausedImages != null ? List.copyOf(pausedImages) : List.of();
        this.signals = signals != null ? Map.copyOf(signals) : Map.of();
        this.artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
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
    public Map<String, String> signals() { return signals; }
    public List<ArtifactRef> artifacts() { return artifacts; }

    public boolean isPaused() { return status == Status.PAUSED; }

    public AgentResult withMaterializedOutput(Object materializedOutput, String materializedRawOutput,
                                               List<ArtifactRef> materializedArtifacts) {
        return new AgentResult(taskId, status, materializedOutput, materializedRawOutput,
                errorMessage, errorKind, messageToUser, resumeState, List.of(), signals,
                materializedArtifacts);
    }

    public AgentResult withSignals(Map<String, String> updatedSignals) {
        return new AgentResult(taskId, status, output, rawOutput, errorMessage, errorKind,
                messageToUser, resumeState, pausedImages, updatedSignals, artifacts);
    }

    public static AgentResult success(String taskId, Object output, String rawOutput) {
        return new AgentResult(taskId, Status.SUCCESS, output, rawOutput, null, null, Map.of(), List.of(), Map.of());
    }

    public static AgentResult success(String taskId, Object output, String rawOutput, Map<String, String> signals) {
        return new AgentResult(taskId, Status.SUCCESS, output, rawOutput, null, null, Map.of(), List.of(), signals);
    }

    public static AgentResult failed(String taskId, String errorMessage) {
        return new AgentResult(taskId, Status.FAILED, null, null, errorMessage, null, Map.of(), List.of(), Map.of());
    }

    public static AgentResult failed(String taskId, String errorMessage, ErrorKind errorKind) {
        return new AgentResult(taskId, Status.FAILED, null, null, errorMessage, errorKind, null,
                Map.of(), List.of(), Map.of());
    }

    public static ErrorKind classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return ErrorKind.NONE;
        }
        String message = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(message, "rate limit", "rate_limit", "too many requests", "429", "quota", "限流")) {
            return ErrorKind.RATE_LIMIT;
        }
        if (containsAny(message, "timeout", "timed out", "超时")) {
            return ErrorKind.TIMEOUT;
        }
        if (containsAny(message, "401", "403", "unauthorized", "forbidden", "api key", "apikey",
                "token expired", "context token", "鉴权", "认证")) {
            return ErrorKind.AUTH;
        }
        if (containsAny(message, "validation", "invalid argument", "参数")) {
            return ErrorKind.VALIDATION;
        }
        if (containsAny(message, "500", "502", "503", "upstream", "server error", "bad gateway")) {
            return ErrorKind.UPSTREAM;
        }
        if (containsAny(message, "tool", "mcp", "工具")) {
            return ErrorKind.TOOL;
        }
        return ErrorKind.UNKNOWN;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, List.of(), Map.of());
    }

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState,
                                      List<ModelReply.ImagePayload> pausedImages) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, pausedImages, Map.of());
    }
}
