package com.youkeda.project.wechatproject.agent.orchestration;

/**
 * 子模型执行结果。
 */
public class AgentResult {

    public enum Status { SUCCESS, FAILED, PARTIAL }

    private final String taskId;
    private final Status status;
    private final Object output;
    private final String rawOutput;
    private final String errorMessage;

    public AgentResult(String taskId, Status status, Object output, String rawOutput, String errorMessage) {
        this.taskId = taskId;
        this.status = status;
        this.output = output;
        this.rawOutput = rawOutput;
        this.errorMessage = errorMessage;
    }

    public String taskId() { return taskId; }
    public Status status() { return status; }
    public Object output() { return output; }
    public String rawOutput() { return rawOutput; }
    public String errorMessage() { return errorMessage; }

    public static AgentResult success(String taskId, Object output, String rawOutput) {
        return new AgentResult(taskId, Status.SUCCESS, output, rawOutput, null);
    }

    public static AgentResult failed(String taskId, String errorMessage) {
        return new AgentResult(taskId, Status.FAILED, null, null, errorMessage);
    }
}
