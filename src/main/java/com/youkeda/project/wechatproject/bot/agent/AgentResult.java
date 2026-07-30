package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.model.ModelReply;

import java.util.List;
import java.util.Map;

public class AgentResult {

    public enum Status { SUCCESS, FAILED, PAUSED }

    private final String taskId;
    private final Status status;
    private final Object output;
    private final String rawOutput;
    private final String errorMessage;
    private final String messageToUser;
    private final Map<String, Object> resumeState;
    private final List<ModelReply.ImagePayload> pausedImages;

    public AgentResult(String taskId, Status status, Object output, String rawOutput,
                       String errorMessage, String messageToUser, Map<String, Object> resumeState,
                       List<ModelReply.ImagePayload> pausedImages) {
        this.taskId = taskId;
        this.status = status;
        this.output = output;
        this.rawOutput = rawOutput;
        this.errorMessage = errorMessage;
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

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, List.of());
    }

    public static AgentResult paused(String taskId, String messageToUser, Map<String, Object> resumeState,
                                      List<ModelReply.ImagePayload> pausedImages) {
        return new AgentResult(taskId, Status.PAUSED, null, null, null, messageToUser, resumeState, pausedImages);
    }
}
