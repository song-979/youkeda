package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.context.ContextEngineeringProperties;
import com.youkeda.project.wechatproject.bot.context.ToolLoopContextManager;
import com.youkeda.project.wechatproject.bot.context.ToolTranscriptStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutChatModelTests {

    @Test
    void stopsToolLoopAfterConfiguredModelRounds() {
        CountingModel delegate = new CountingModel();
        TimeoutChatModel guarded = new TimeoutChatModel(delegate, 0, 2, 10000);
        Prompt toolResponse = new Prompt(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", "result")))
                .build());

        guarded.call(new Prompt("start"));
        guarded.call(toolResponse);
        ChatResponse stopped = guarded.call(toolResponse);

        assertThat(delegate.calls).hasValue(2);
        assertThat(stopped.getResult().getOutput().getText()).contains("最大轮数");
    }

    @Test
    void stopsBeforeDelegateWhenContextTokenBudgetIsExceeded() {
        CountingModel delegate = new CountingModel();
        TimeoutChatModel guarded = new TimeoutChatModel(delegate, 0, 20, 1024);

        ChatResponse stopped = guarded.call(new Prompt("large context ".repeat(2000)));

        assertThat(delegate.calls).hasValue(0);
        assertThat(stopped.getResult().getOutput().getText()).contains("token");
    }

    @Test
    void compressesAccumulatedToolResultsBeforeApplyingHardContextLimit() {
        CountingModel delegate = new CountingModel();
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        properties.setCompressedToolResultsMaxChars(200);
        ToolLoopContextManager manager = new ToolLoopContextManager(
                ToolTranscriptStore.noop(), 2_048, properties);
        TimeoutChatModel guarded = new TimeoutChatModel(delegate, 0, 20, 2_048, manager);
        Prompt prompt = new Prompt(List.of(
                new AssistantMessage("working"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1", "large_tool", "large result ".repeat(2_000))))
                        .build()));

        ChatResponse response = guarded.call(prompt);

        assertThat(delegate.calls).hasValue(1);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    }

    private static final class CountingModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
