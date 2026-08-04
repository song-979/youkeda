package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmContextRelevanceClassifierTests {

    @Test
    void usesConfiguredOrchestratorModelForAmbiguousRelevance() {
        CapturingClient client = new CapturingClient();
        LlmContextRelevanceClassifier classifier = new LlmContextRelevanceClassifier(
                client, request -> ContextRelevance.NEW_TOPIC, "planner-model");

        ContextRelevance relevance = classifier.classify(ContextBuildRequest.builder()
                .currentMessage("what about this approach")
                .stage(ContextStage.RESUME)
                .recentHistory(List.of(new ChatRequest.Message("assistant", "previous approach")))
                .build());

        assertThat(relevance).isEqualTo(ContextRelevance.RELATED);
        assertThat(client.options.model()).isEqualTo("planner-model");
        assertThat(client.options.maxTokens()).isEqualTo(64);
    }

    @Test
    void initialOrchestratorPlanUsesRulesWithoutExtraModelCall() {
        CapturingClient client = new CapturingClient();
        LlmContextRelevanceClassifier classifier = new LlmContextRelevanceClassifier(
                client, request -> ContextRelevance.RELATED, "planner-model");

        ContextRelevance relevance = classifier.classify(ContextBuildRequest.builder()
                .currentMessage("plan a multi-step trip")
                .stage(ContextStage.PLAN)
                .audience(ContextAudience.ORCHESTRATOR)
                .build());

        assertThat(relevance).isEqualTo(ContextRelevance.RELATED);
        assertThat(client.calls).isZero();
    }

    @Test
    void activeDagCanStillBeClassifiedAsNewTopic() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"relevance\":\"NEW_TOPIC\"}";
        LlmContextRelevanceClassifier classifier = new LlmContextRelevanceClassifier(
                client, request -> ContextRelevance.RELATED, "planner-model");
        ContextTaskState active = new ContextTaskState(
                "wf", "WAITING_USER", 1, "travel", null, "book a trip",
                List.of(new ContextTaskRecord(
                        "travel", "travel", "TRAVEL", "WAITING_USER", List.of(),
                        "book a taxi", null, null, "Which car type do you want?", Map.of())));

        ContextRelevance relevance = classifier.classify(ContextBuildRequest.builder()
                .currentMessage("write a Java sorting example")
                .stage(ContextStage.RESUME)
                .taskState(active)
                .build());

        assertThat(relevance).isEqualTo(ContextRelevance.NEW_TOPIC);
        assertThat(client.messages.getFirst().getContent().toString())
                .contains("book a trip", "Which car type do you want?");
    }

    private static final class CapturingClient implements AiModelClient {
        private ChatCallOptions options;
        private List<ChatRequest.Message> messages = List.of();
        private String response = "{\"relevance\":\"RELATED\"}";
        private int calls;

        @Override
        public String chat(String userMessage, List<String> images, List<ChatRequest.Message> history) {
            throw new AssertionError("full message API expected");
        }

        @Override
        public String chat(List<ChatRequest.Message> messages, ChatCallOptions options) {
            calls++;
            this.messages = List.copyOf(messages);
            this.options = options;
            return response;
        }
    }
}
