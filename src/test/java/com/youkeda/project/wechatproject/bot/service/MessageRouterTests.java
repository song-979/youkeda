package com.youkeda.project.wechatproject.bot.service;

import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentCapability;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentRegistry;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentTask;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentUnit;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.MessageRouter;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ModelReply;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestrationResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestratorAgent;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestratorProperties;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.TaskScratchpad;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.UserRequest;
import com.youkeda.project.wechatproject.bot.tool.LocalFileTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRouterTests {

    @TempDir
    Path tempDir;

    @Test
    void routesReminderRequestsToStructuredAutomationPlanGuidance() throws IOException {
        RecordingChatAgent chatAgent = new RecordingChatAgent();
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new UnsupportedReminderOrchestrator(),
                registry,
                null,
                null,
                null,
                properties);

        ModelReply reply = router.route(
                "user-1",
                "\u4eca\u5929\u4e0b\u53483:25\u7ed9\u6211\u53d1\u4e00\u6761\u6d88\u606f\uff0c\u5185\u5bb9\u662f\u4e0b\u73ed\u4e86",
                List.of());

        assertThat(reply.getTextContent()).isEqualTo("chat handled reminder");
        assertThat(chatAgent.instructions).hasSize(1);
        assertThat(chatAgent.instructions.getFirst()).contains("apply_automation_plan");
        assertThat(chatAgent.instructions.getFirst()).contains("TEXT_REMINDER");
    }

    @Test
    void routesTimedWeatherTellRequestsToLlmTaskGuidance() throws IOException {
        RecordingChatAgent chatAgent = new RecordingChatAgent();
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new UnsupportedReminderOrchestrator(),
                registry,
                null,
                null,
                null,
                properties);

        router.route(
                "user-1",
                "7\u670823\u65e5\u665a\u4e0a9:11\u544a\u8bc9\u6211\u676d\u5dde\u5e02\u4f59\u676d\u533a\u7684\u5929\u6c14",
                List.of());

        assertThat(chatAgent.instructions).hasSize(1);
        assertThat(chatAgent.instructions.getFirst()).contains("apply_automation_plan");
        assertThat(chatAgent.instructions.getFirst()).contains("LLM_TASK");
        assertThat(chatAgent.instructions.getFirst()).contains("Do not query weather");
        assertThat(chatAgent.instructions.getFirst()).contains("Do not simulate future results");
    }

    @Test
    void stalePreparedLocalFileDoesNotTurnLaterChatReplyIntoMixedFileReply() throws Exception {
        Path staleFile = Files.writeString(tempDir.resolve("stale.txt"), "old file");
        LocalFileTools fileTools = new LocalFileTools();
        fileTools.setAllowedRoots(List.of(tempDir.toString()));
        fileTools.sendLocalFile(staleFile.toString());

        RecordingChatAgent chatAgent = new RecordingChatAgent("plain chat reply");
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new ExecuteChatOrchestrator(),
                registry,
                null,
                null,
                null,
                properties);

        ModelReply reply = router.route("user-1", "\u4f60\u597d", List.of());

        assertThat(reply.getType()).isEqualTo(ModelReply.Type.TEXT);
        assertThat(reply.getTextContent()).isEqualTo("plain chat reply");
        assertThat(reply.getFilePayload()).isNull();
    }

    private static class UnsupportedReminderOrchestrator implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.COMPLETED)
                    .reasoning("unsupported reminder")
                    .finalReply(ModelReply.text("unsupported reminder"))
                    .build();
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new UnsupportedOperationException("reflection disabled in this test");
        }
    }

    private static class RecordingChatAgent implements AgentUnit {
        private final List<String> instructions = new ArrayList<>();
        private final String response;

        RecordingChatAgent() {
            this("chat handled reminder");
        }

        RecordingChatAgent(String response) {
            this.response = response;
        }

        @Override
        public String getName() {
            return "CHAT";
        }

        @Override
        public AgentCapability getCapability() {
            return new AgentCapability(
                    "chat-generation",
                    "Handles tool-assisted runtime tasks.",
                    List.of("runtime-tools"),
                    "text");
        }

        @Override
        public AgentResult execute(AgentTask task) {
            instructions.add(task.instruction());
            return AgentResult.success(task.taskId(), response, response);
        }
    }

    private static class ExecuteChatOrchestrator implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("execute chat")
                    .tasks(List.of(new AgentTask("CHAT", request.text(), Map.of())))
                    .build();
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new UnsupportedOperationException("reflection disabled in this test");
        }
    }
}
