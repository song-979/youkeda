package com.youkeda.project.wechatproject.bot.orchestrator;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanDraft;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DagPlanningAgentImplTests {

    @Test
    void usesPlannerModelAndDagOnlyPrompt() {
        CapturingClient client = new CapturingClient();
        AgentProperties properties = new AgentProperties();
        properties.setModel("chat-model");
        properties.setIntentModel("planner-model");
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent()), null);
        DagPlanningAgentImpl planner = new DagPlanningAgentImpl(client, properties, registry, null);

        DagPlanDraft result = planner.planDag(
                new UserRequest("u", "answer directly", List.of(), List.of()), List.of());

        assertThat(result.status()).isEqualTo(DagPlanDraft.Status.COMPLETED);
        assertThat(client.options.model()).isEqualTo("planner-model");
        assertThat(client.options.temperature()).isZero();
        String systemContext = client.messages.stream()
                .filter(message -> "system".equals(message.getRole()))
                .map(message -> String.valueOf(message.getContent()))
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(systemContext)
                .contains("backend-owned DAG executor", "Available agent units", "CHAT")
                .doesNotContain("scratchpad", "legacy");
        planner.close();
    }

    @Test
    void stopsBlockedPlannerAtConfiguredHardDeadline() {
        AgentProperties properties = new AgentProperties();
        properties.setModel("chat-model");
        properties.setIntentModel("planner-model");
        properties.setIntentReadTimeoutMs(1_000);
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent()), null);
        DagPlanningAgentImpl planner = new DagPlanningAgentImpl(
                new BlockingClient(), properties, registry, null);

        long startedAt = System.nanoTime();
        DagPlanDraft result = planner.planDag(
                new UserRequest("u", "plan a long task", List.of(), List.of()), List.of());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(result.status()).isEqualTo(DagPlanDraft.Status.UNAVAILABLE);
        assertThat(result.reasoning()).contains("timed out after 1000 ms");
        assertThat(elapsedMs).isBetween(900L, 2_500L);
        planner.close();
    }

    private static AgentUnit chatAgent() {
        return new AgentUnit() {
            @Override public String getName() { return "CHAT"; }
            @Override public AgentCapability getCapability() {
                return new AgentCapability("chat", "general chat", List.of("answer"), "text");
            }
            @Override public AgentResult execute(AgentTask task) {
                return AgentResult.success(task.taskId(), "ok", "ok");
            }
        };
    }

    private static final class CapturingClient implements AiModelClient {
        private ChatCallOptions options;
        private List<ChatRequest.Message> messages = List.of();

        @Override
        public String chat(String userMessage, List<String> images, List<ChatRequest.Message> history) {
            throw new AssertionError("planner should use per-call options API");
        }

        @Override
        public String chat(List<ChatRequest.Message> messages, ChatCallOptions options) throws IOException {
            this.messages = List.copyOf(messages);
            this.options = options;
            return "{\"status\":\"completed\",\"reasoning\":\"done\",\"final_reply\":\"ok\"}";
        }
    }

    private static final class BlockingClient implements AiModelClient {
        @Override
        public String chat(String userMessage, List<String> images, List<ChatRequest.Message> history) {
            throw new AssertionError("planner should use per-call options API");
        }

        @Override
        public String chat(List<ChatRequest.Message> messages, ChatCallOptions options) throws IOException {
            try {
                Thread.sleep(10_000);
                return "{}";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked planner interrupted", e);
            }
        }
    }
}
