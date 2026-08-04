package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;

import java.util.List;
import java.util.Map;

public class DagNode {

    public enum Status {
        PENDING,
        READY,
        RUNNING,
        SUCCEEDED,
        RETRY_WAIT,
        WAITING_USER,
        FAILED,
        SKIPPED,
        INVALIDATED,
        BLOCKED
    }

    private final String id;
    private final String key;
    private final String agentType;
    private final String instruction;
    private final String contextNote;
    private final List<String> dependsOn;
    private final Map<String, Object> parameters;
    private final int maxAttempts;
    private Status status;
    private int attemptCount;
    private long nextAttemptAt;
    private AgentResult result;
    private String inputFingerprint;
    private int executionRevision;
    private int resultRevision;

    public DagNode(String id, String key, String agentType, String instruction, String contextNote,
                   List<String> dependsOn, Map<String, Object> parameters, int maxAttempts) {
        this(id, key, agentType, instruction, contextNote, dependsOn, parameters, maxAttempts,
                Status.PENDING, 0, 0L, null, null, 0, 0);
    }

    public DagNode(String id, String key, String agentType, String instruction, String contextNote,
                   List<String> dependsOn, Map<String, Object> parameters, int maxAttempts,
                   Status status, int attemptCount, long nextAttemptAt, AgentResult result) {
        this(id, key, agentType, instruction, contextNote, dependsOn, parameters, maxAttempts,
                status, attemptCount, nextAttemptAt, result, null, 0, 0);
    }

    public DagNode(String id, String key, String agentType, String instruction, String contextNote,
                   List<String> dependsOn, Map<String, Object> parameters, int maxAttempts,
                   Status status, int attemptCount, long nextAttemptAt, AgentResult result,
                   String inputFingerprint, int executionRevision, int resultRevision) {
        this.id = id;
        this.key = key;
        this.agentType = agentType;
        this.instruction = instruction;
        this.contextNote = contextNote;
        this.dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.maxAttempts = Math.max(1, maxAttempts);
        this.status = status != null ? status : Status.PENDING;
        this.attemptCount = Math.max(0, attemptCount);
        this.nextAttemptAt = Math.max(0L, nextAttemptAt);
        this.result = result;
        this.inputFingerprint = inputFingerprint;
        this.executionRevision = Math.max(0, executionRevision);
        this.resultRevision = Math.max(0, resultRevision);
    }

    public String id() { return id; }
    public String key() { return key; }
    public String agentType() { return agentType; }
    public String instruction() { return instruction; }
    public String contextNote() { return contextNote; }
    public List<String> dependsOn() { return dependsOn; }
    public Map<String, Object> parameters() { return parameters; }
    public int maxAttempts() { return maxAttempts; }
    public Status status() { return status; }
    public int attemptCount() { return attemptCount; }
    public long nextAttemptAt() { return nextAttemptAt; }
    public AgentResult result() { return result; }
    public String inputFingerprint() { return inputFingerprint; }
    public int executionRevision() { return executionRevision; }
    public int resultRevision() { return resultRevision; }

    public void setStatus(Status status) { this.status = status; }
    public void setNextAttemptAt(long nextAttemptAt) { this.nextAttemptAt = Math.max(0L, nextAttemptAt); }
    public void setResult(AgentResult result) { this.result = result; }
    public int beginAttempt() { return ++attemptCount; }

    public int beginAttempt(int dagRevision, String fingerprint) {
        executionRevision = Math.max(0, dagRevision);
        inputFingerprint = fingerprint;
        return ++attemptCount;
    }

    public void markResultRevision(int dagRevision) {
        resultRevision = Math.max(0, dagRevision);
    }

    public void resetForRetry(boolean clearAttempts) {
        status = Status.PENDING;
        nextAttemptAt = 0L;
        result = null;
        inputFingerprint = null;
        executionRevision = 0;
        resultRevision = 0;
        if (clearAttempts) {
            attemptCount = 0;
        }
    }

    public boolean isSuccessfulTerminal() {
        return status == Status.SUCCEEDED || status == Status.SKIPPED;
    }

    public boolean isSideEffectRisk() {
        return "BROWSER".equals(agentType) || "TRAVEL".equals(agentType);
    }
}
