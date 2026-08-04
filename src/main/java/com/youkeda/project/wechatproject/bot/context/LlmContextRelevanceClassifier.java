package com.youkeda.project.wechatproject.bot.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
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
            An active DAG alone does not imply RESUME_TASK. If the latest request is unrelated to
            the DAG goal or waiting prompt, return NEW_TOPIC so the old workflow can be abandoned.
            """;

    private final AiService.AiModelClient aiClient;
    private final ContextRelevanceClassifier fallback;
    private final String model;

    public LlmContextRelevanceClassifier(AiService.AiModelClient aiClient,
                                         ContextRelevanceClassifier fallback) {
        this(aiClient, fallback, null);
    }

    public LlmContextRelevanceClassifier(AiService.AiModelClient aiClient,
                                         ContextRelevanceClassifier fallback,
                                         String model) {
        this.aiClient = aiClient;
        this.fallback = fallback != null ? fallback : new RuleBasedContextRelevanceClassifier();
        this.model = model;
    }

    @Override
    public ContextRelevance classify(ContextBuildRequest request) {
        ContextRelevance ruleResult = fallback.classify(request);
        if (request != null
                && request.audience() == ContextAudience.ORCHESTRATOR
                && request.stage() == ContextStage.PLAN
                && (request.taskState() == null || request.taskState().isEmpty())) {
            return ruleResult;
        }
        if (request != null
                && request.audience() == ContextAudience.SUB_AGENT
                && request.recentHistory().isEmpty()
                && (request.taskState() == null || request.taskState().isEmpty())) {
            return ruleResult;
        }
        if (ruleResult == ContextRelevance.RESUME_TASK
                || ruleResult == ContextRelevance.TOOL_DEPENDENT
                || ruleResult == ContextRelevance.CONTINUATION) {
            return ruleResult;
        }
        if (aiClient == null) {
            return ruleResult;
        }
        try {
            String response = aiClient.chat(
                    List.of(new ChatRequest.Message("user", userPrompt(request))),
                    ChatCallOptions.deterministic(SYSTEM_PROMPT, model, 64));
            ContextRelevance relevance = parseRelevance(response);
            return relevance != null ? relevance : ruleResult;
        } catch (Exception e) {
            log.warn("LLM context relevance classification failed, falling back to rules: {}", e.getMessage());
            return ruleResult;
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
        if (safeRequest.taskState() != null && !safeRequest.taskState().isEmpty()) {
            ContextTaskState taskState = safeRequest.taskState();
            sb.append("\nActive DAG context:\n")
                    .append("status=").append(taskState.dagStatus())
                    .append(" current_node=").append(taskState.currentNodeId()).append("\n");
            if (taskState.summary() != null && !taskState.summary().isBlank()) {
                sb.append("goal=").append(truncate(taskState.summary(), 800)).append("\n");
            }
            sb.append("node_count=")
                    .append(safeRequest.taskState().records().size())
                    .append("\n");
            int taskFrom = Math.max(0, taskState.records().size() - 8);
            for (int i = taskFrom; i < taskState.records().size(); i++) {
                ContextTaskRecord record = taskState.records().get(i);
                sb.append("- ").append(record.key()).append(" agent=").append(record.agentType())
                        .append(" status=").append(record.status())
                        .append(" instruction=").append(truncate(record.instruction(), 300)).append("\n");
                if (record.messageToUser() != null && !record.messageToUser().isBlank()) {
                    sb.append("  waiting_prompt=").append(truncate(record.messageToUser(), 400)).append("\n");
                }
                if (record.result() != null && !record.result().isBlank()) {
                    sb.append("  result=").append(truncate(record.result(), 300)).append("\n");
                }
            }
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
