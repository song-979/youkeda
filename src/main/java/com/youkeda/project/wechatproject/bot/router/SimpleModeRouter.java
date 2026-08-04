package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentExecutionContext;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.context.ContextAudience;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simple mode router: keyword matching → single agent execution, no orchestrator, no reflection.
 * Falls back to CHAT when no keyword matches.
 * Keywords are derived dynamically from each agent's {@link AgentCapability#routingKeywords()}.
 */
public class SimpleModeRouter {

    private static final Logger log = LoggerFactory.getLogger(SimpleModeRouter.class);

    private final AgentRegistry registry;
    private final ConversationMemory memory;
    private final Map<String, List<String>> agentKeywords;

    public SimpleModeRouter(AgentRegistry registry, ConversationMemory memory) {
        this.registry = registry;
        this.memory = memory;
        this.agentKeywords = buildAgentKeywords(registry);
    }

    private static Map<String, List<String>> buildAgentKeywords(AgentRegistry registry) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (var entry : registry.all().entrySet()) {
            List<String> keywords = entry.getValue().getCapability().routingKeywords();
            if (!keywords.isEmpty()) {
                map.put(entry.getKey(), keywords);
            }
        }
        return Map.copyOf(map);
    }

    public ModelReply route(String userId, String text, List<String> imageBase64Urls) throws IOException {
        List<ChatRequest.Message> history = memory != null ? memory.getHistory(userId, text) : List.of();

        String agentType = matchAgent(text);
        log.info("simple mode: matched agent={} for text={}",
                agentType, text != null ? text.substring(0, Math.min(50, text.length())) : "<empty>");

        AgentUnit agent = registry.get(agentType);
        if (agent == null) {
            log.warn("simple mode: agent {} not found, falling back to CHAT", agentType);
            agent = registry.get("CHAT");
            agentType = "CHAT";
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        if ("CHAT".equals(agentType)) {
            if (!imageBase64Urls.isEmpty()) {
                params.put("imageUrls", imageBase64Urls);
            }
        }

        String instruction = text != null ? text : "";
        AgentTask task = new AgentTask(agentType, instruction, params)
                .withExecutionContext(new AgentExecutionContext(
                        userId,
                        ContextStage.EXECUTE,
                        ContextAudience.DIRECT,
                        "CHAT".equals(agentType) ? history : List.of(),
                        ContextTaskState.empty(),
                        imageBase64Urls,
                        null));

        SkillTools.setCurrentAgent(agentType);
        AgentResult result;
        try {
            result = agent.execute(task);
        } finally {
            SkillTools.clearCurrentAgent();
        }

        log.info("simple mode: agent={} status={}", agentType, result.status());

        if (memory != null) {
            String replyText = extractTextContent(agentType, result);
            memory.append(userId, text, replyText);
        }

        return buildReply(agentType, result);
    }

    String matchAgent(String text) {
        if (text == null || text.isBlank()) {
            return "CHAT";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (var entry : agentKeywords.entrySet()) {
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    return entry.getKey();
                }
            }
        }
        return "CHAT";
    }

    private String extractTextContent(String agentType, AgentResult result) {
        Object output = result.output();
        if (output instanceof String s) {
            return s;
        }
        if (output instanceof GeneratedImage) {
            return "[image-generated]";
        }
        if (output instanceof SpeechAgent.TtsOutput) {
            return "[voice-generated]";
        }
        return "";
    }

    private ModelReply buildReply(String agentType, AgentResult result) {
        return buildAgentReply(agentType, result);
    }

    /**
     * Converts a single agent's result into a ModelReply.
     * Shared with {@link MessageRouter} for reply assembly consistency.
     */
    static ModelReply buildAgentReply(String agentType, AgentResult result) {
        if (result.status() == AgentResult.Status.FAILED) {
            String msg = result.output() instanceof String s ? s : "操作失败，请稍后重试。";
            return ModelReply.text(msg);
        }

        Object output = result.output();

        if ("IMAGE_GEN".equals(agentType)) {
            if (output instanceof GeneratedImage gi) {
                return ModelReply.image(gi.bytes(), gi.fileName());
            }
            if (output instanceof byte[] imgBytes) {
                return ModelReply.image(imgBytes, "generated.png");
            }
        }

        if ("SPEECH_GEN".equals(agentType)) {
            if (output instanceof SpeechAgent.TtsOutput tts) {
                return ModelReply.voice(tts.audioBytes(), tts.format(), tts.durationMs(), tts.sampleRate());
            }
        }

        String text = output instanceof String s ? s : "已完成";
        return ModelReply.text(text);
    }
}
