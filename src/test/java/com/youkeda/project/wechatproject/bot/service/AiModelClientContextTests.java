package com.youkeda.project.wechatproject.bot.service;

import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelClientContextTests {

    @Test
    void defaultFullMessageAdapterPreservesEverySystemLayer() throws Exception {
        CapturingLegacyClient client = new CapturingLegacyClient();

        client.chat(List.of(
                new ChatRequest.Message("system", "fixed protocol"),
                new ChatRequest.Message("system", "dag state"),
                new ChatRequest.Message("system", "dependency result"),
                new ChatRequest.Message("user", "execute instruction")),
                new ChatCallOptions(null, "model", 0.0d, 64));

        assertThat(client.systemPrompt)
                .contains("fixed protocol", "dag state", "dependency result");
        assertThat(client.userMessage).isEqualTo("execute instruction");
    }

    private static final class CapturingLegacyClient implements AiModelClient {
        private String userMessage;
        private String systemPrompt;

        @Override
        public String chat(String userMessage, List<String> images, List<ChatRequest.Message> history) {
            throw new AssertionError("system-aware overload expected");
        }

        @Override
        public String chat(String userMessage, List<String> images, List<ChatRequest.Message> history,
                           String systemPrompt) {
            this.userMessage = userMessage;
            this.systemPrompt = systemPrompt;
            return "ok";
        }
    }
}
