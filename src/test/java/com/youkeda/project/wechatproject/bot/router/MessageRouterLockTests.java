package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestrationResult;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestratorAgent;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestratorProperties;
import com.youkeda.project.wechatproject.bot.orchestrator.TaskScratchpad;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRouterLockTests {

    @Test
    void releasesIdleUserLockAfterRequestCompletes() throws IOException {
        AgentUnit chatAgent = new AgentUnit() {
            @Override
            public String getName() {
                return "CHAT";
            }

            @Override
            public AgentCapability getCapability() {
                return new AgentCapability("chat", "test", List.of(), "text");
            }

            @Override
            public AgentResult execute(AgentTask task) {
                return AgentResult.success(task.taskId(), "ok", "ok");
            }
        };
        OrchestratorAgent orchestrator = new OrchestratorAgent() {
            @Override
            public OrchestrationResult plan(UserRequest request) {
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.EXECUTE)
                        .tasks(List.of(new AgentTask("CHAT", request.text(), Map.of())))
                        .build();
            }

            @Override
            public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest request) {
                throw new UnsupportedOperationException("reflection disabled");
            }
        };
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        MessageRouter router = new MessageRouter(
                orchestrator, registry, null, null, null, properties,
                new SimpleModeRouter(registry, null));

        router.route("user-1", "hello", List.of());

        assertThat(router.activeUserLockCount()).isZero();
    }
}
