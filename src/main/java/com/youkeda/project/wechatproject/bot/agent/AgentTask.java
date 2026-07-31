package com.youkeda.project.wechatproject.bot.agent;

import java.util.Map;
import java.util.UUID;

public class AgentTask {

    private final String taskId;
    private final String agentType;
    private final String instruction;
    private final Map<String, Object> parameters;
    /** Owning end user of this task, used to restore per-user tool context in worker threads. */
    private final String userId;

    public AgentTask(String agentType, String instruction, Map<String, Object> parameters) {
        this(UUID.randomUUID().toString().substring(0, 8), agentType, instruction, parameters, null);
    }

    private AgentTask(String taskId, String agentType, String instruction, Map<String, Object> parameters,
                      String userId) {
        this.taskId = taskId;
        this.agentType = agentType;
        this.instruction = instruction;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.userId = userId;
    }

    public AgentTask withParameters(Map<String, Object> newParameters) {
        return new AgentTask(taskId, agentType, instruction, newParameters, userId);
    }

    public AgentTask withInstruction(String newInstruction) {
        return new AgentTask(taskId, agentType, newInstruction, parameters, userId);
    }

    public AgentTask withUserId(String newUserId) {
        return new AgentTask(taskId, agentType, instruction, parameters, newUserId);
    }

    public String taskId() { return taskId; }
    public String agentType() { return agentType; }
    public String instruction() { return instruction; }
    public Map<String, Object> parameters() { return parameters; }
    public String userId() { return userId; }
}
