package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.context.CharacterContextTokenEstimator;
import com.youkeda.project.wechatproject.bot.context.ContextAudience;
import com.youkeda.project.wechatproject.bot.context.ContextBuildRequest;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.context.DefaultContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.RuleBasedContextRelevanceClassifier;
import com.youkeda.project.wechatproject.bot.memory.AgentMemory;
import com.youkeda.project.wechatproject.bot.memory.FileBasedAgentMemory;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared adapter between typed agent execution envelopes and the context engineering service. */
public final class AgentContextAssembler {

    private AgentContextAssembler() {
    }

    public static ContextPackage build(ContextEngineeringService service,
                                       AgentTask task,
                                       String fixedSystemPrompt,
                                       AgentMemory agentMemory) {
        ContextEngineeringService effectiveService = service != null ? service
                : new DefaultContextEngineeringService(
                        new RuleBasedContextRelevanceClassifier(), new CharacterContextTokenEstimator());
        AgentExecutionContext supplied = task.executionContext();
        String userId = supplied != null ? supplied.userId() : stringParam(task, "userId");
        List<ChatRequest.Message> history = supplied != null ? supplied.recentHistory() : List.of();
        List<String> images = supplied != null ? supplied.imageUrls() : stringListParam(task, "imageUrls");
        ContextTaskState taskState = supplied != null ? supplied.taskState() : ContextTaskState.empty();
        ContextStage stage = supplied != null ? supplied.stage() : ContextStage.EXECUTE;
        ContextAudience audience = supplied != null ? supplied.audience() : ContextAudience.SUB_AGENT;
        String imageSummary = supplied != null ? supplied.rememberedImageSummary() : null;

        String memorySummary = "";
        if (agentMemory instanceof FileBasedAgentMemory fileMemory && userId != null) {
            memorySummary = fileMemory.buildContextPrompt(userId, task.instruction());
        }

        List<ChatRequest.Message> fixed = fixedSystemPrompt != null && !fixedSystemPrompt.isBlank()
                ? List.of(new ChatRequest.Message("system", fixedSystemPrompt)) : List.of();
        return effectiveService.build(ContextBuildRequest.builder()
                .userId(userId)
                .currentMessage(task.instruction())
                .stage(stage)
                .audience(audience)
                .recentHistory(history)
                .taskState(taskState)
                .fixedPromptMessages(fixed)
                .includeCapabilityLayer(false)
                .imageBase64Urls(images)
                .rememberedImageSummary(imageSummary)
                .agentMemorySummary(memorySummary)
                .build());
    }

    public static List<Message> toSpringMessages(ContextPackage context, List<String> imageUrls) {
        List<Message> messages = new ArrayList<>();
        List<ChatRequest.Message> source = context != null ? context.messages() : List.of();
        int lastUser = -1;
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) != null && "user".equalsIgnoreCase(source.get(i).getRole())) {
                lastUser = i;
            }
        }
        for (int i = 0; i < source.size(); i++) {
            ChatRequest.Message message = source.get(i);
            if (message == null) {
                continue;
            }
            String content = String.valueOf(message.getContent());
            String role = message.getRole() != null ? message.getRole().toLowerCase(Locale.ROOT) : "";
            if (i == lastUser && imageUrls != null && !imageUrls.isEmpty()) {
                List<Media> media = imageUrls.stream()
                        .map(url -> new Media(detectMimeType(url), URI.create(url)))
                        .toList();
                messages.add(UserMessage.builder().text(content).media(media).build());
            } else {
                messages.add(switch (role) {
                    case "system" -> new SystemMessage(content);
                    case "assistant" -> new AssistantMessage(content);
                    default -> new UserMessage(content);
                });
            }
        }
        return messages;
    }

    public static LegacyCall toLegacyCall(ContextPackage context) {
        List<ChatRequest.Message> source = context != null ? context.messages() : List.of();
        List<String> systems = new ArrayList<>();
        List<ChatRequest.Message> history = new ArrayList<>();
        String userMessage = "";
        int lastUser = -1;
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) != null && "user".equalsIgnoreCase(source.get(i).getRole())) {
                lastUser = i;
            }
        }
        for (int i = 0; i < source.size(); i++) {
            ChatRequest.Message message = source.get(i);
            if (message == null) {
                continue;
            }
            if ("system".equalsIgnoreCase(message.getRole())) {
                systems.add(String.valueOf(message.getContent()));
            } else if (i == lastUser) {
                userMessage = String.valueOf(message.getContent());
            } else {
                history.add(message);
            }
        }
        return new LegacyCall(userMessage, List.copyOf(history), String.join("\n\n", systems));
    }

    private static MimeType detectMimeType(String dataUrl) {
        if (dataUrl != null && dataUrl.contains("image/jpeg")) return MimeTypeUtils.IMAGE_JPEG;
        if (dataUrl != null && dataUrl.contains("image/gif")) return MimeTypeUtils.IMAGE_GIF;
        if (dataUrl != null && dataUrl.contains("image/webp")) {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        return MimeTypeUtils.IMAGE_PNG;
    }

    private static String stringParam(AgentTask task, String key) {
        Object value = task.parameters().get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static List<String> stringListParam(AgentTask task, String key) {
        Object value = task.parameters().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public record LegacyCall(String userMessage, List<ChatRequest.Message> history,
                             String systemPrompt) {
    }
}
