package com.youkeda.project.wechatproject.bot.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.JsonExtractUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules-first SIMPLE/COMPLEX admission gate. The model is used only for ambiguous requests. */
public class TaskComplexityRouter {

    private static final Logger log = LoggerFactory.getLogger(TaskComplexityRouter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MODEL_FAILURE_COOLDOWN_MS = 120_000L;
    private static final int MAX_MODEL_INPUT_CHARS = 1_500;

    private static final String SYSTEM_PROMPT = """
            Classify execution complexity for a multi-agent backend.
            Return ONLY compact JSON:
            {"complexity":"SIMPLE|COMPLEX","reason_code":"SHORT_CODE"}

            SIMPLE means one agent can handle the request as one execution unit. Long writing,
            translation, summarization, analysis, one search, one reminder, one image, one route,
            and one browser action are still SIMPLE.

            COMPLEX means there are two or more executable tasks with dependencies, parallel work,
            multiple agent domains, rollback/retry coordination, or a result from one task must be
            consumed by another. Linguistic difficulty and output length alone do not make a task complex.
            """;

    private static final Set<String> CHITCHAT = Set.of(
            "你好", "您好", "hi", "hello", "hey", "嗨", "在吗", "早上好", "下午好", "晚上好",
            "晚安", "谢谢", "谢谢你", "感谢", "好的", "好", "嗯", "哦", "ok", "okay", "再见", "拜拜");
    private static final List<String> EXPLICIT_COMPLEX_MARKERS = List.of(
            "多步骤", "分步骤", "并行执行", "串行执行", "任务依赖", "依赖关系", "支持回滚",
            "失败重试", "失败后重试", "完成以后再", "完成之后再", "做完以后", "做完之后",
            "然后", "接着");
    private static final List<String> MULTI_INTENT_MARKERS = List.of(
            "并且", "同时", "另外", "顺便", "以及");
    private static final List<String> AMBIGUOUS_MARKERS = List.of(
            "并且", "同时", "另外", "顺便", "以及", "完整方案", "完整流程", "整个流程",
            "规划", "计划", "拆解", "一整套", "一系列", "综合处理");
    private static final List<String> SINGLE_TASK_PREFIXES = List.of(
            "写", "帮我写", "生成", "帮我生成", "翻译", "润色", "解释", "介绍", "总结",
            "分析", "比较", "查询", "查一下", "搜索", "提醒我", "创建提醒", "画", "帮我画",
            "朗读", "念一下", "打开", "登录", "导航", "规划路线", "查天气", "告诉我");
    private static final List<String> QUESTION_MARKERS = List.of(
            "什么", "为什么", "怎么", "如何", "是否", "谁", "哪里",
            "what", "why", "how", "who", "where");
    private static final Pattern ORDERED_SEQUENCE = Pattern.compile(
            "先.{1,160}(然后|接着|之后|再)", Pattern.DOTALL);
    private static final Pattern NUMBERED_STEP = Pattern.compile(
            "(?:^|[\\s，,；;])(?:[1-9][.、)]|第[一二三四五六七八九十]+步)");

    private final AiModelClient modelClient;
    private final AgentRegistry registry;
    private final String model;
    private final AtomicLong modelUnavailableUntil = new AtomicLong();

    public TaskComplexityRouter(AiModelClient modelClient, AgentRegistry registry, String model) {
        this.modelClient = modelClient;
        this.registry = registry;
        this.model = model;
    }

    public Assessment assess(UserRequest request) {
        String text = request != null && request.text() != null ? request.text().trim() : "";
        Assessment rule = assessByRules(request, text);
        if (rule != null) {
            return rule;
        }

        long now = System.currentTimeMillis();
        if (modelClient == null || now < modelUnavailableUntil.get()) {
            return fallback(text, modelClient == null ? "MODEL_DISABLED" : "MODEL_CIRCUIT_OPEN");
        }

        long startedAt = System.currentTimeMillis();
        try {
            String response = modelClient.chat(
                    List.of(new ChatRequest.Message("user", modelPrompt(request, text))),
                    ChatCallOptions.deterministic(SYSTEM_PROMPT, model, 96));
            Assessment parsed = parseModelAssessment(response, System.currentTimeMillis() - startedAt);
            if (parsed == null) {
                throw new IllegalArgumentException("classifier returned invalid JSON");
            }
            modelUnavailableUntil.set(0L);
            return parsed;
        } catch (Exception e) {
            modelUnavailableUntil.set(System.currentTimeMillis() + MODEL_FAILURE_COOLDOWN_MS);
            log.warn("[ROUTE] complexity model unavailable; opening {}s cooldown and using rules: {}",
                    MODEL_FAILURE_COOLDOWN_MS / 1_000L, e.getMessage());
            return fallback(text, "MODEL_UNAVAILABLE");
        }
    }

    private Assessment assessByRules(UserRequest request, String text) {
        if (text.isBlank()) {
            return Assessment.simple(Source.RULE, "EMPTY_OR_IMAGE_ONLY", 0L);
        }
        String normalized = stripNoise(text);
        if (normalized.length() <= 12 && CHITCHAT.contains(normalized)) {
            return Assessment.simple(Source.RULE, "CHITCHAT", 0L);
        }

        Set<String> matchedAgents = matchedAgents(text);
        if (matchedAgents.size() > 1) {
            return Assessment.complex(Source.RULE, "MULTIPLE_AGENT_DOMAINS", 0L);
        }
        if (hasExplicitSequence(text) || countNumberedSteps(text) >= 2) {
            return Assessment.complex(Source.RULE, "EXPLICIT_MULTI_STEP", 0L);
        }

        boolean ambiguous = containsAny(text, AMBIGUOUS_MARKERS);
        if (request != null && !request.imageBase64Urls().isEmpty() && !ambiguous) {
            return Assessment.simple(Source.RULE, "SINGLE_IMAGE_REQUEST", 0L);
        }
        // A known single domain stays simple even when its wording includes "plan" or "workflow".
        if (matchedAgents.size() == 1 && !containsAny(text, MULTI_INTENT_MARKERS)) {
            return Assessment.simple(Source.RULE, "SINGLE_AGENT_DOMAIN", 0L);
        }
        if (!ambiguous && (text.length() <= 80 || startsWithAny(text, SINGLE_TASK_PREFIXES))) {
            return Assessment.simple(Source.RULE, "SINGLE_TASK_SHAPE", 0L);
        }
        if (!ambiguous && isQuestion(text) && text.length() <= 240) {
            return Assessment.simple(Source.RULE, "SINGLE_QUESTION", 0L);
        }
        return null;
    }

    private Set<String> matchedAgents(String text) {
        Set<String> matches = new LinkedHashSet<>();
        if (registry == null) {
            return matches;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        registry.all().forEach((name, unit) -> {
            boolean matched = unit.getCapability().routingKeywords().stream()
                    .filter(keyword -> keyword != null && !keyword.isBlank())
                    .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                    .anyMatch(lower::contains);
            if (matched) {
                matches.add(name);
            }
        });
        return matches;
    }

    private static boolean hasExplicitSequence(String text) {
        return ORDERED_SEQUENCE.matcher(text).find() || containsAny(text, EXPLICIT_COMPLEX_MARKERS);
    }

    private static int countNumberedSteps(String text) {
        int count = 0;
        Matcher matcher = NUMBERED_STEP.matcher(text);
        while (matcher.find() && count < 2) {
            count++;
        }
        return count;
    }

    private static Assessment parseModelAssessment(String response, long latencyMs) {
        String json = JsonExtractUtil.extractJsonObject(response);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            String value = root.path("complexity").asText("").trim().toUpperCase(Locale.ROOT);
            String reason = sanitizeReason(root.path("reason_code").asText("MODEL_DECISION"));
            return switch (value) {
                case "SIMPLE" -> Assessment.simple(Source.MODEL, reason, latencyMs);
                case "COMPLEX" -> Assessment.complex(Source.MODEL, reason, latencyMs);
                default -> null;
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Assessment fallback(String text, String reason) {
        // Explicit complex requests were already handled by rules; ambiguous long requests fail closed.
        boolean complex = containsAny(text, AMBIGUOUS_MARKERS) && text.length() > 120;
        return complex
                ? Assessment.complex(Source.FALLBACK, reason + "_RISK_COMPLEX", 0L)
                : Assessment.simple(Source.FALLBACK, reason + "_DEFAULT_SIMPLE", 0L);
    }

    private static String modelPrompt(UserRequest request, String text) {
        StringBuilder prompt = new StringBuilder("Latest request:\n")
                .append(truncate(text, MAX_MODEL_INPUT_CHARS));
        if (request != null && !request.history().isEmpty()) {
            prompt.append("\n\nRecent context:\n");
            int from = Math.max(0, request.history().size() - 4);
            for (int i = from; i < request.history().size(); i++) {
                ChatRequest.Message message = request.history().get(i);
                prompt.append(message.getRole()).append(": ")
                        .append(truncate(String.valueOf(message.getContent()), 300)).append('\n');
            }
        }
        return prompt.toString();
    }

    private static boolean isQuestion(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return text.endsWith("?") || text.endsWith("？")
                || containsAny(lower, QUESTION_MARKERS);
    }

    private static boolean containsAny(String text, List<String> candidates) {
        String lower = text.toLowerCase(Locale.ROOT);
        return candidates.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(lower::contains);
    }

    private static boolean startsWithAny(String text, List<String> candidates) {
        String lower = text.toLowerCase(Locale.ROOT);
        return candidates.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(lower::startsWith);
    }

    private static String stripNoise(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String sanitizeReason(String value) {
        if (value == null || value.isBlank()) {
            return "MODEL_DECISION";
        }
        return truncate(value.replaceAll("[^A-Za-z0-9_-]", "_"), 80);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    public enum Complexity { SIMPLE, COMPLEX }

    public enum Source { RULE, MODEL, FALLBACK }

    public record Assessment(Complexity complexity, Source source, String reasonCode, long latencyMs) {
        static Assessment simple(Source source, String reasonCode, long latencyMs) {
            return new Assessment(Complexity.SIMPLE, source, reasonCode, latencyMs);
        }

        static Assessment complex(Source source, String reasonCode, long latencyMs) {
            return new Assessment(Complexity.COMPLEX, source, reasonCode, latencyMs);
        }

        public boolean isSimple() {
            return complexity == Complexity.SIMPLE;
        }
    }
}
