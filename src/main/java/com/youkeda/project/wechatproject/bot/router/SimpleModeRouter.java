package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
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
 */
public class SimpleModeRouter {

    private static final Logger log = LoggerFactory.getLogger(SimpleModeRouter.class);

    private static final Map<String, List<String>> AGENT_KEYWORDS = Map.of(
            "TRAVEL", List.of(
                    "导航", "地图", "路线", "天气", "打车", "附近", "怎么去", "在哪", "多远",
                    "公交", "地铁", "开车", "步行", "骑行", "叫车", "出行", "叫车"),
            "BROWSER", List.of(
                    "搜索", "上网", "查一下", "打开网页", "登录", "帮我搜", "百度", "网上",
                    "浏览器", "打开链接", "查资料", "浏览"),
            "IMAGE_GEN", List.of(
                    "生成图片", "画一张", "画个", "生成一张", "P图", "做图", "生成图",
                    "画图", "绘画", "生成图像", "帮我画"),
            "SPEECH_GEN", List.of(
                    "朗读", "语音", "朗诵", "读出来", "念", "播报", "说出来")
    );

    private final AgentRegistry registry;
    private final ConversationMemory memory;

    public SimpleModeRouter(AgentRegistry registry, ConversationMemory memory) {
        this.registry = registry;
        this.memory = memory;
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
        if ("CHAT".equals(agentType)) {
            if (!imageBase64Urls.isEmpty()) {
                params.put("imageUrls", imageBase64Urls);
            }
            if (!history.isEmpty()) {
                params.put("history", history);
            }
        }

        String instruction = text != null ? text : "";
        AgentTask task = new AgentTask(agentType, instruction, params);

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
        for (var entry : AGENT_KEYWORDS.entrySet()) {
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
