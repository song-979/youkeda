package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

public record ContextBuildRequest(
        String userId,
        String currentMessage,
        ContextStage stage,
        ContextAudience audience,
        List<ChatRequest.Message> recentHistory,
        ContextTaskState taskState,
        List<AgentCapabilityView> agentCapabilities,
        List<ChatRequest.Message> fixedPromptMessages,
        boolean includeCapabilityLayer,
        List<String> imageBase64Urls,
        String rememberedImageSummary,
        String agentMemorySummary,
        ContextBudget budget) {

    public ContextBuildRequest {
        stage = stage != null ? stage : ContextStage.PLAN;
        audience = audience != null ? audience : ContextAudience.ORCHESTRATOR;
        recentHistory = recentHistory != null ? List.copyOf(recentHistory) : List.of();
        taskState = taskState != null ? taskState : ContextTaskState.empty();
        agentCapabilities = agentCapabilities != null ? List.copyOf(agentCapabilities) : List.of();
        fixedPromptMessages = fixedPromptMessages != null ? List.copyOf(fixedPromptMessages) : List.of();
        imageBase64Urls = imageBase64Urls != null ? List.copyOf(imageBase64Urls) : List.of();
        budget = budget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String userId;
        private String currentMessage;
        private ContextStage stage;
        private ContextAudience audience;
        private List<ChatRequest.Message> recentHistory;
        private ContextTaskState taskState;
        private List<AgentCapabilityView> agentCapabilities;
        private List<ChatRequest.Message> fixedPromptMessages;
        private boolean includeCapabilityLayer = true;
        private List<String> imageBase64Urls;
        private String rememberedImageSummary;
        private String agentMemorySummary;
        private ContextBudget budget;

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder currentMessage(String currentMessage) {
            this.currentMessage = currentMessage;
            return this;
        }

        public Builder stage(ContextStage stage) {
            this.stage = stage;
            return this;
        }

        public Builder audience(ContextAudience audience) {
            this.audience = audience;
            return this;
        }

        public Builder recentHistory(List<ChatRequest.Message> recentHistory) {
            this.recentHistory = recentHistory;
            return this;
        }

        public Builder taskState(ContextTaskState taskState) {
            this.taskState = taskState;
            return this;
        }

        public Builder agentCapabilities(List<AgentCapabilityView> agentCapabilities) {
            this.agentCapabilities = agentCapabilities;
            return this;
        }

        public Builder fixedPromptMessages(List<ChatRequest.Message> fixedPromptMessages) {
            this.fixedPromptMessages = fixedPromptMessages;
            return this;
        }

        public Builder includeCapabilityLayer(boolean includeCapabilityLayer) {
            this.includeCapabilityLayer = includeCapabilityLayer;
            return this;
        }

        public Builder imageBase64Urls(List<String> imageBase64Urls) {
            this.imageBase64Urls = imageBase64Urls;
            return this;
        }

        public Builder rememberedImageSummary(String rememberedImageSummary) {
            this.rememberedImageSummary = rememberedImageSummary;
            return this;
        }

        public Builder agentMemorySummary(String agentMemorySummary) {
            this.agentMemorySummary = agentMemorySummary;
            return this;
        }

        public Builder budget(ContextBudget budget) {
            this.budget = budget;
            return this;
        }

        public ContextBuildRequest build() {
            return new ContextBuildRequest(userId, currentMessage, stage, audience, recentHistory, taskState,
                    agentCapabilities, fixedPromptMessages, includeCapabilityLayer,
                    imageBase64Urls, rememberedImageSummary, agentMemorySummary, budget);
        }
    }
}
