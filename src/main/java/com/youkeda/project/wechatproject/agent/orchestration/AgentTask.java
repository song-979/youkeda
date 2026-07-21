package com.youkeda.project.wechatproject.agent.orchestration;

import java.util.Map;
import java.util.UUID;

/**
 * 编排模型下发的子任务。
 */
public class AgentTask {

    private final String taskId;
    private final String agentType;
    private final String instruction;
    private final Map<String, Object> parameters;

    public AgentTask(String agentType, String instruction, Map<String, Object> parameters) {
        this(UUID.randomUUID().toString().substring(0, 8), agentType, instruction, parameters);
    }

    private AgentTask(String taskId, String agentType, String instruction, Map<String, Object> parameters) {
        this.taskId = taskId;
        this.agentType = agentType;
        this.instruction = instruction;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }

    public AgentTask withInstruction(String newInstruction) {
        return new AgentTask(taskId, agentType, newInstruction, parameters);
    }

    public AgentTask withParameters(Map<String, Object> newParameters) {
        return new AgentTask(taskId, agentType, instruction, newParameters);
    }

    public AgentTask withParameter(String key, Object value) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(parameters);
        merged.put(key, value);
        return new AgentTask(taskId, agentType, instruction, merged);
    }

    public AgentTask withAgentType(String newAgentType) {
        return new AgentTask(taskId, newAgentType, instruction, parameters);
    }

    public AgentTask copy() {
        return new AgentTask(taskId, agentType, instruction, parameters);
    }

    public String taskId() { return taskId; }
    public String agentType() { return agentType; }
    public String instruction() { return instruction; }
    public Map<String, Object> parameters() { return parameters; }
}
