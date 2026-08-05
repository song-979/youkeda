package com.youkeda.project.wechatproject.bot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
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
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.router.SimpleModeRouter;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageHandlerTests {

    @Test
    void handlesMessagesForTheSameUserInArrivalOrderWithoutBlockingCallback() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch bothCompleted = new CountDownLatch(2);
        List<String> handledTexts = new CopyOnWriteArrayList<>();

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
                handledTexts.add(task.instruction());
                if ("first".equals(task.instruction())) {
                    firstStarted.countDown();
                    try {
                        allowFirstToFinish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                bothCompleted.countDown();
                return AgentResult.success(task.taskId(), "ok", "ok");
            }
        };
        AgentRegistry registry = new AgentRegistry(List.of(chatAgent), null);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setReflectionEnabled(false);
        MessageRouter router = new MessageRouter(new ChatOnlyOrchestrator(), registry, null, null, null,
                properties, new SimpleModeRouter(registry, null));

        MessageHandler handler = new MessageHandler(mock(ILinkClient.class), mock(MessageBridge.class), router,
                null, null, null, null, null);
        try {
            handler.onMessages(List.of(message("user-1", "first"), message("user-1", "second")));

            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handledTexts).containsExactly("first");

            allowFirstToFinish.countDown();
            assertThat(bothCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handledTexts).containsExactly("first", "second");
        } finally {
            handler.destroy();
        }
    }

    private static WeixinMessage message(String userId, String text) {
        TextItem textItem = mock(TextItem.class);
        when(textItem.getText()).thenReturn(text);
        MessageItem item = mock(MessageItem.class);
        when(item.getType()).thenReturn(1);
        when(item.getText_item()).thenReturn(textItem);
        WeixinMessage message = mock(WeixinMessage.class);
        when(message.getFrom_user_id()).thenReturn(userId);
        when(message.getItem_list()).thenReturn(List.of(item));
        return message;
    }

    private static final class ChatOnlyOrchestrator implements OrchestratorAgent {
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
    }
}
