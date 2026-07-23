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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRouterTests {

    @Test
    void routesReminderRequestsToChatToolsInsteadOfTrustingUnsupportedPlan() throws IOException {
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
                "今天下午3:25给我发一条消息，内容是下班了",
                List.of());

        assertThat(reply.getTextContent()).isEqualTo("chat handled reminder");
        assertThat(chatAgent.instructions).containsExactly("今天下午3:25给我发一条消息，内容是下班了");
    }

    private static class UnsupportedReminderOrchestrator implements OrchestratorAgent {
        @Override
        public OrchestrationResult plan(UserRequest request) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.COMPLETED)
                    .reasoning("用户请求在指定时间发送消息，但系统没有定时消息或提醒功能，无法执行。")
                    .finalReply(ModelReply.text("不支持定时消息"))
                    .build();
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            throw new UnsupportedOperationException("reflection disabled in this test");
        }
    }

    private static class RecordingChatAgent implements AgentUnit {
        private final List<String> instructions = new ArrayList<>();

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
            return AgentResult.success(task.taskId(), "chat handled reminder", "chat handled reminder");
        }
    }
}
