package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight rule-based router that sits in front of the full Orchestrator.
 * <p>
 * Most user messages are either chitchat or single-domain requests that do not need
 * an LLM planning round. This router classifies them with cheap deterministic rules:
 * <ul>
 *   <li>L1: chitchat / very short small talk → direct CHAT task</li>
 *   <li>L2: unambiguous single-domain request (weather, navigation, image gen) →
 *       direct task for that agent</li>
 *   <li>L3: anything ambiguous, compound, or cross-domain → {@code null}, the caller
 *       falls through to the full Orchestrator plan</li>
 * </ul>
 * Direct routes are marked {@code skipReflection} so a high-confidence single-step
 * plan costs zero orchestration LLM calls instead of two (plan + reflect).
 * <p>
 * The rules are intentionally conservative: when in doubt we return {@code null}
 * and pay for the full plan. A false direct-route is more expensive (wrong behavior)
 * than a false full-plan (a few extra tokens).
 */
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** Chitchat that never needs tools or planning (matched after stripping punctuation/whitespace). */
    private static final List<String> CHITCHAT_PHRASES = List.of(
            "你好", "您好", "hi", "hello", "hey", "嗨", "喂", "在吗", "在么", "早上好", "晚上好",
            "下午好", "中午好", "晚安", "谢谢", "谢谢你", "感谢", "多谢", "辛苦了", "好的", "好",
            "嗯", "嗯嗯", "哦", "ok", "okay", "行", "可以", "再见", "拜拜", "拜", "你是谁",
            "你叫什么", "你叫什么名字");

    /**
     * Words signalling a compound or cross-domain request. Any message containing one
     * of these is never direct-routed — it needs the orchestrator to split tasks.
     */
    private static final List<String> COMPOUND_MARKERS = List.of(
            "然后", "接着", "之后", "顺便", "再帮", "另外", "还有", "并且", "同时",
            "提醒", "定时", "闹钟", "每天", "每周", "一下班就", "记一下", "记住",
            "发给我", "语音", "朗读", "读出来", "总结完", "查完", "搜索完");

    private static final List<String> IMAGE_GEN_KEYWORDS = List.of(
            "生成图片", "画一张", "画个", "生成一张", "p图", "做图", "生成图",
            "画图", "绘画", "生成图像", "帮我画", "画一幅");

    private static final List<String> TRAVEL_KEYWORDS = List.of(
            "天气", "怎么去", "怎么走", "路线", "导航", "打车", "叫车", "多远",
            "附近有什么", "附近有", "地铁怎么坐", "公交怎么坐");

    /** Messages longer than this are never direct-routed; long text usually carries nuance. */
    private static final int MAX_DIRECT_ROUTE_CHARS = 60;

    private final AgentRegistry registry;

    public IntentRouter(AgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * Try to build a direct execution plan for the request without calling the
     * orchestrator LLM. Returns {@code null} when the message should go through the
     * full plan/reflect loop.
     */
    public OrchestrationResult tryDirectRoute(UserRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return null;
        }
        // Resumed PAUSED conversations embed a resume block — never shortcut those,
        // the orchestrator must see the full resume context.
        if (text.contains("上一轮任务状态")) {
            return null;
        }
        // Messages with images need multimodal reasoning about user intent — let the
        // orchestrator plan (CHAT-only plans are handled by specialCasePlan already).
        if (!request.imageBase64Urls().isEmpty()) {
            return null;
        }
        if (text.length() > MAX_DIRECT_ROUTE_CHARS) {
            return null;
        }

        String normalized = stripNoise(text);

        // L1: chitchat
        if (isChitchat(normalized)) {
            log.info("IntentRouter L1: chitchat → direct CHAT");
            return directChat(text, "chitchat");
        }

        // Compound / cross-domain markers → full plan
        if (containsAny(text, COMPOUND_MARKERS)) {
            return null;
        }

        // L2: single-domain rules, most specific first
        if (registry.contains("IMAGE_GEN") && containsAny(text, IMAGE_GEN_KEYWORDS)) {
            log.info("IntentRouter L2: image-gen keywords → direct IMAGE_GEN");
            return directTask("IMAGE_GEN", "Create an image based on this user request: " + text,
                    "image-gen keyword");
        }

        if (registry.contains("TRAVEL") && containsAny(text, TRAVEL_KEYWORDS)) {
            log.info("IntentRouter L2: travel keywords → direct TRAVEL");
            return directTask("TRAVEL", text, "travel keyword");
        }

        // L3: fall through to the full orchestrator
        return null;
    }

    private OrchestrationResult directChat(String text, String reason) {
        return directTask("CHAT", text, reason);
    }

    private OrchestrationResult directTask(String agentType, String instruction, String reason) {
        return OrchestrationResult.builder()
                .status(OrchestrationResult.Status.EXECUTE)
                .reasoning("IntentRouter direct route: " + reason)
                .tasks(List.of(new AgentTask(agentType, instruction, Map.of())))
                .skipReflection(true)
                .build();
    }

    private static boolean isChitchat(String normalized) {
        if (normalized.isEmpty() || normalized.length() > 12) {
            return false;
        }
        for (String phrase : CHITCHAT_PHRASES) {
            if (normalized.equals(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String stripNoise(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, List<String> keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
