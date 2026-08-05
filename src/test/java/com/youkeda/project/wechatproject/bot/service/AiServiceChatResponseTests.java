package com.youkeda.project.wechatproject.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiServiceChatResponseTests {

    @Test
    void ignoresProviderSpecificReasoningFields() throws Exception {
        String json = """
                {
                  "id": "response-1",
                  "provider_metadata": {"region": "test"},
                  "choices": [{
                    "index": 0,
                    "finish_reason": "stop",
                    "provider_choice_field": true,
                    "message": {
                      "role": "assistant",
                      "content": "{\\\"status\\\":\\\"dag\\\"}",
                      "reasoning_content": "private chain of thought",
                      "provider_message_field": "ignored"
                    }
                  }]
                }
                """;

        AiService.ChatResponse response = new ObjectMapper()
                .readValue(json, AiService.ChatResponse.class);

        assertThat(response.extractContent()).isEqualTo("{\"status\":\"dag\"}");
    }

    @Test
    void rejectsReasoningOnlyResponseWithoutExposingReasoningAsContent() throws Exception {
        String json = """
                {
                  "id": "response-2",
                  "choices": [{
                    "index": 0,
                    "finish_reason": "length",
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "reasoning_content": "internal reasoning that must not become output"
                    }
                  }]
                }
                """;

        AiService.ChatResponse response = new ObjectMapper()
                .readValue(json, AiService.ChatResponse.class);

        assertThat(response.extractContent()).isNull();
        assertThat(response.hasReasoningContent()).isTrue();
        assertThat(response.firstFinishReason()).isEqualTo("length");
        assertThatThrownBy(response::requireContent)
                .isInstanceOf(AiService.EmptyModelResponseException.class)
                .hasMessageContaining("no usable assistant content");
    }
}
