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
import com.youkeda.project.wechatproject.memory.InMemoryConversationMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    void fastChatSkipsOrchestratorForSimpleTextWhenEnabled() throws IOException {
        RecordingChatAgent chatAgent = new RecordingChatAgent("fast reply");
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setFastChatEnabled(true);
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new FailingOrchestrator(),
                registry,
                null,
                null,
                null,
                properties);

        ModelReply reply = router.route("user-1", "\u4f60\u597d", List.of());

        assertThat(reply.getTextContent()).isEqualTo("fast reply");
        assertThat(chatAgent.instructions).containsExactly("\u4f60\u597d");
    }

    @Test
    void multiTaskRepliesIncludeMergedResultsAndMarkdownChecklist() throws IOException {
        EchoChatAgent chatAgent = new EchoChatAgent();
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new MultiTaskOrchestrator(),
                registry,
                null,
                null,
                null,
                properties);

        ModelReply reply = router.route("user-1", "do alpha, beta, gamma", List.of());

        assertThat(reply.getType()).isEqualTo(ModelReply.Type.MIXED);
        assertThat(reply.getTextContent())
                .contains("\u4efb\u52a1\u7ed3\u679c\uff1a")
                .contains("done: alpha")
                .contains("done: beta")
                .contains("done: gamma");
        assertThat(reply.getFilePayload()).isNotNull();
        assertThat(reply.getFilePayload().fileName()).isEqualTo("\u4efb\u52a1\u6e05\u5355.md");

        String checklist = new String(reply.getFilePayload().bytes(), StandardCharsets.UTF_8);
        assertThat(checklist)
                .contains("# \u4efb\u52a1\u6e05\u5355")
                .contains("- [x]")
                .contains("Alpha task")
                .contains("Beta task")
                .contains("Gamma task")
                .contains("\u5168\u90e8\u6e05\u5355\u4efb\u52a1\u5df2\u5b8c\u6210\u3002");
    }

    @Test
    void browserTaskDoesNotAttachRememberedImages() throws IOException {
        RecordingTaskChatAgent chatAgent = new RecordingTaskChatAgent();
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        InMemoryConversationMemory memory = new InMemoryConversationMemory(2, 30);
        memory.rememberImageContext("user-1", List.of("data:image/png;base64,AAAA"), "old image");

        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new ExecuteChatOrchestrator(),
                registry,
                memory,
                null,
                null,
                properties);

        router.route("user-1", "\u6253\u5f00B\u7ad9\u770b\u4eca\u65e5\u70ed\u641c", List.of());

        assertThat(chatAgent.tasks).hasSize(1);
        assertThat(chatAgent.tasks.getFirst().parameters()).doesNotContainKey("imageUrls");
    }

    @Test
    void explicitPosterAndSpeechItemsAreRoutedToSpecializedAgentsWhenPlannerMissesThem() throws IOException {
        RecordingTaskChatAgent chatAgent = new RecordingTaskChatAgent();
        RecordingAgent imageAgent = new RecordingAgent("IMAGE_GEN", new byte[] {1, 2, 3}, "[image generated]");
        RecordingAgent speechAgent = new RecordingAgent("SPEECH_GEN",
                new OrchestrationService.SpeechAgent.TtsOutput(new byte[] {4, 5, 6}, "wav", 1000, 24000),
                "[speech generated]");
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent, imageAgent, speechAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(
                new PlannerThatMissesSpecializedTasks(),
                registry,
                null,
                null,
                null,
                properties);

        router.route("user-1", """
                9. 生成一张发到家庭微信群的邀请海报，文字是“周末迪士尼小队出发”，风格明亮、亲子、旅行感，但不要使用官方商标。
                10. 把一句行程播报合成为语音：“周末迪士尼行程已经开始规划，我会把交通、天气、门票、提醒和预算都整理好。”
                """, List.of());

        assertThat(imageAgent.tasks).hasSize(1);
        assertThat(imageAgent.tasks.getFirst().instruction())
                .contains("周末迪士尼小队出发")
                .contains("Do not use official Disney trademarks");
        assertThat(speechAgent.tasks).hasSize(1);
        assertThat(speechAgent.tasks.getFirst().instruction())
                .isEqualTo("周末迪士尼行程已经开始规划，我会把交通、天气、门票、提醒和预算都整理好。");
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

    private static class EchoChatAgent implements AgentUnit {
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
            String output = "done: " + task.instruction();
            return AgentResult.success(task.taskId(), output, output);
        }
    }

    private static class RecordingTaskChatAgent implements AgentUnit {
        private final List<AgentTask> tasks = new ArrayList<>();

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
            tasks.add(task);
            return AgentResult.success(task.taskId(), "ok", "ok");
        }
    }

    private static class RecordingAgent implements AgentUnit {
        private final String name;
        private final Object output;
        private final String rawOutput;
        private final List<AgentTask> tasks = new ArrayList<>();

        RecordingAgent(String name, Object output, String rawOutput) {
            this.name = name;
            this.output = output;
            this.rawOutput = rawOutput;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AgentCapability getCapability() {
            return new AgentCapability(name.toLowerCase(), name, List.of(name), name.toLowerCase());
        }

        @Override
        public AgentResult execute(AgentTask task) {
            tasks.add(task);
            return AgentResult.success(task.taskId(), output, rawOutput);
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

    private static class MultiTaskOrchestrator implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("split user request into checklist tasks")
                    .tasks(List.of(
                            new AgentTask("CHAT", "alpha", Map.of("checklist_item", "Alpha task")),
                            new AgentTask("CHAT", "beta", Map.of("checklist_item", "Beta task")),
                            new AgentTask("CHAT", "gamma", Map.of("checklist_item", "Gamma task"))
                    ))
                    .build();
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new UnsupportedOperationException("reflection disabled in this test");
        }
    }

    private static class PlannerThatMissesSpecializedTasks implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("planner only returned chat")
                    .tasks(List.of(new AgentTask("CHAT", "handle ordinary trip planning items", Map.of())))
                    .build();
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new UnsupportedOperationException("reflection disabled in this test");
        }
    }

    private static class FailingOrchestrator implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            throw new AssertionError("orchestrator should not be called");
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new AssertionError("orchestrator should not be called");
        }
    }
}
