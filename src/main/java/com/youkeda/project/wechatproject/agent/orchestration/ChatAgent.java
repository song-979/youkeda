package com.youkeda.project.wechatproject.agent.orchestration;

import com.youkeda.project.wechatproject.agent.AiModelClient;
import com.youkeda.project.wechatproject.agent.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class
ChatAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    private final AiModelClient chatClient;

    public ChatAgent(AiModelClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String getName() {
        return "CHAT";
    }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability(
                "chat-generation",
                "Handles dialogue, writing, analysis, and vision-language responses.",
                List.of("dialogue", "writing", "analysis", "vision"),
                "text"
        );
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        log.info("ChatAgent executing task: instruction={}", task.instruction());

        List<String> imageUrls = stringList(task.parameters().get("imageUrls"));
        List<ChatRequest.Message> history = historyList(task.parameters().get("history"));

        String response = chatClient.chatStream(task.instruction(), imageUrls, history);
        log.info("ChatAgent response: {} chars", response != null ? response.length() : 0);
        return AgentResult.success(task.taskId(), response, response);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static List<ChatRequest.Message> historyList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(ChatRequest.Message.class::isInstance)
                    .map(ChatRequest.Message.class::cast)
                    .toList();
        }
        return List.of();
    }
}
