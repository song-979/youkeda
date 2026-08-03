package com.youkeda.project.wechatproject.bot.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.JsonExtractUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

public class LlmContextRelevanceClassifier implements ContextRelevanceClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmContextRelevanceClassifier.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_HISTORY_MESSAGES = 8;

    private static final String SYSTEM_PROMPT = """
            You classify whether the user's latest request depends on conversation context.
            Return ONLY compact JSON: {"relevance":"NEW_TOPIC|RELATED|CONTINUATION|RESUME_TASK|TOOL_DEPENDENT"}.
            NEW_TOPIC means the request can be answered without prior conversation.
            RELATED means prior conversation may help but is not strictly a continuation.
            CONTINUATION means the user refers to previous turns with phrases like continue, that, previous.
            RESUME_TASK means the user is continuing a paused or interrupted long task.
            TOOL_DEPENDENT means the user asks to use results produced by tools or sub-agents.
            """;

    private final AiService.AiModelClient aiClient;
    private final ContextRelevanceClassifier fallback;

    public LlmContextRelevanceClassifier(AiService.AiModelClient aiClient,
                                         ContextRelevanceClassifier fallback) {
        this.aiClient = aiClient;
        this.fallback = fallback != null ? fallback : new RuleBasedContextRelevanceClassifier();
    }

    @Override
    public ContextRelevance classify(ContextBuildRequest request) {
        if (aiClient == null) {
            return fallback.classify(request);
        }
        try {
            String response = aiClient.chat(userPrompt(request), List.of(),
                    List.of(new ChatRequest.Message("system", SYSTEM_PROMPT)));
            ContextRelevance relevance = parseRelevance(response);
            return relevance != null ? relevance : fallback.classify(request);
        } catch (Exception e) {
            log.warn("LLM context relevance classification failed, falling back to rules: {}", e.getMessage());
            return fallback.classify(request);
        }
    }

    private static String userPrompt(ContextBuildRequest request) {
        ContextBuildRequest safeRequest = request != null ? request : ContextBuildRequest.builder().build();
        StringBuilder sb = new StringBuilder();
        sb.append("Latest user request:\n")
                .append(safeRequest.currentMessage() != null ? safeRequest.currentMessage() : "")
                .append("\n\nRecent conversation tail:\n");
        List<ChatRequest.Message> history = safeRequest.recentHistory();
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int i = from; i < history.size(); i++) {
            ChatRequest.Message message = history.get(i);
            sb.append(message.getRole()).append(": ")
                    .append(truncate(String.valueOf(message.getContent()), 400))
                    .append("\n");
        }
        if (safeRequest.scratchpad() != null && !safeRequest.scratchpad().isEmpty()) {
            sb.append("\nThere is a saved task scratchpad with ")
                    .append(safeRequest.scratchpad().records().size())
                    .append(" record(s).");
        }
        return sb.toString();
    }

    private static ContextRelevance parseRelevance(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String json = JsonExtractUtil.extractJsonObject(response);
        String value = null;
        if (json != null) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(json);
                JsonNode relevance = node.path("relevance");
                value = relevance.isTextual() ? relevance.asText() : null;
            } catch (Exception ignored) {
                value = null;
            }
        }
        if (value == null) {
            value = response.trim();
        }
        try {
            return ContextRelevance.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
