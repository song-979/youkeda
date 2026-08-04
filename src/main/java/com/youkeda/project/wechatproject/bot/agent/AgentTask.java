package com.youkeda.project.wechatproject.bot.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AgentTask {

    private final String taskId;
    private final String agentType;
    private final String instruction;
    private final String contextNote;
    private final Map<String, Object> parameters;
    private final AgentExecutionContext executionContext;

    public AgentTask(String agentType, String instruction, Map<String, Object> parameters) {
        this(agentType, instruction, null, parameters);
    }

    public AgentTask(String agentType, String instruction, String contextNote, Map<String, Object> parameters) {
        this(UUID.randomUUID().toString().substring(0, 8), agentType, instruction, contextNote, parameters, null);
    }

    private AgentTask(String taskId, String agentType, String instruction, String contextNote,
                      Map<String, Object> parameters, AgentExecutionContext executionContext) {
        this.taskId = taskId;
        this.agentType = agentType;
        this.instruction = instruction;
        this.contextNote = contextNote;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.executionContext = executionContext;
    }

    public static AgentTask restore(String taskId, String agentType, String instruction,
                                    String contextNote, Map<String, Object> parameters) {
        String effectiveTaskId = taskId != null && !taskId.isBlank()
                ? taskId
                : UUID.randomUUID().toString().substring(0, 8);
        return new AgentTask(effectiveTaskId, agentType, instruction, contextNote, parameters, null);
    }

    public AgentTask withParameters(Map<String, Object> newParameters) {
        return new AgentTask(taskId, agentType, instruction, contextNote, newParameters, executionContext);
    }

    public AgentTask withInstruction(String newInstruction) {
        return new AgentTask(taskId, agentType, newInstruction, contextNote, parameters, executionContext);
    }

    public AgentTask withExecutionContext(AgentExecutionContext newExecutionContext) {
        return new AgentTask(taskId, agentType, instruction, contextNote, parameters, newExecutionContext);
    }

    public String taskId() { return taskId; }
    public String agentType() { return agentType; }
    public String instruction() { return instruction; }
    public String contextNote() { return contextNote; }
    public Map<String, Object> parameters() { return parameters; }
    public AgentExecutionContext executionContext() { return executionContext; }
}
