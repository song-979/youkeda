package com.youkeda.project.wechatproject.bot.orchestrator;

import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.model.ModelReply;

import java.util.List;

public class OrchestrationResult {

    public enum Status {
        NEEDS_CLARIFICATION,
        EXECUTE,
        COMPLETED
    }

    private final Status status;
    private final String reasoning;
    private final String clarificationQuestion;
    private final List<AgentTask> tasks;
    private final ModelReply finalReply;
    private final TaskScratchpad scratchpad;
    /**
     * When true, the router skips the reflect LLM call after executing the tasks.
     * Set by lightweight routers (IntentRouter) for high-confidence single-step plans
     * where a reflection round would only waste a model call.
     */
    private final boolean skipReflection;

    private OrchestrationResult(Builder builder) {
        this.status = builder.status;
        this.reasoning = builder.reasoning;
        this.clarificationQuestion = builder.clarificationQuestion;
        this.tasks = builder.tasks != null ? List.copyOf(builder.tasks) : List.of();
        this.finalReply = builder.finalReply;
        this.scratchpad = builder.scratchpad != null ? builder.scratchpad : new TaskScratchpad();
        this.skipReflection = builder.skipReflection;
    }

    public Status status() { return status; }
    public String reasoning() { return reasoning; }
    public String clarificationQuestion() { return clarificationQuestion; }
    public List<AgentTask> tasks() { return tasks; }
    public ModelReply finalReply() { return finalReply; }
    public TaskScratchpad scratchpad() { return scratchpad; }
    public boolean skipReflection() { return skipReflection; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Status status;
        private String reasoning;
        private String clarificationQuestion;
        private List<AgentTask> tasks;
        private ModelReply finalReply;
        private TaskScratchpad scratchpad;
        private boolean skipReflection;

        public Builder status(Status status) { this.status = status; return this; }
        public Builder reasoning(String reasoning) { this.reasoning = reasoning; return this; }
        public Builder clarificationQuestion(String q) { this.clarificationQuestion = q; return this; }
        public Builder tasks(List<AgentTask> tasks) { this.tasks = tasks; return this; }
        public Builder finalReply(ModelReply reply) { this.finalReply = reply; return this; }
        public Builder scratchpad(TaskScratchpad sp) { this.scratchpad = sp; return this; }
        public Builder skipReflection(boolean skip) { this.skipReflection = skip; return this; }

        public OrchestrationResult build() {
            return new OrchestrationResult(this);
        }
    }
}
