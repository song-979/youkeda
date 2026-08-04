package com.youkeda.project.wechatproject.bot.agent.chat;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentContextAssembler;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.memory.AgentMemory;
import com.youkeda.project.wechatproject.bot.memory.FileBasedAgentMemory;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ToolLoopContextRuntime;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import com.youkeda.project.wechatproject.bot.tool.chat.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.chat.MotouTool;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationRuntime;
import com.youkeda.project.wechatproject.bot.tool.chat.RagTools;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import com.youkeda.project.wechatproject.bot.tool.travel.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class ChatAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);
    private final AiModelClient chatClient;
    private final AgentProperties agentProperties;
    private final ChatClient toolChatClient;
    private final String toolCategories;
    private final String skillsSummary;
    private final AgentMemory agentMemory;
    private final ContextEngineeringService contextEngineeringService;

    public ChatAgent(AiModelClient chatClient) {
        this(chatClient, null, null, "", "", null);
    }

    public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                     ToolChatClientFactory toolChatClientFactory) {
        this(chatClient, agentProperties, toolChatClientFactory, "", "", null);
    }

    public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                     ToolChatClientFactory toolChatClientFactory, String toolCategories,
                     String skillsSummary) {
        this(chatClient, agentProperties, toolChatClientFactory, toolCategories, skillsSummary, null);
    }

    public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                     ToolChatClientFactory toolChatClientFactory, String toolCategories,
                     String skillsSummary, AgentMemory agentMemory) {
        this(chatClient, agentProperties, toolChatClientFactory, toolCategories, skillsSummary,
                agentMemory, null);
    }

    public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                     ToolChatClientFactory toolChatClientFactory, String toolCategories,
                     String skillsSummary, AgentMemory agentMemory,
                     ContextEngineeringService contextEngineeringService) {
        this.chatClient = chatClient;
        this.agentProperties = agentProperties;
        this.toolChatClient = toolChatClientFactory != null ? toolChatClientFactory.create() : null;
        this.toolCategories = toolCategories != null ? toolCategories : "";
        this.skillsSummary = skillsSummary != null ? skillsSummary : "";
        this.agentMemory = agentMemory;
        this.contextEngineeringService = contextEngineeringService;
    }

    @Override
    public String getName() {
        return "CHAT";
    }

    @Override
    public AgentCapability getCapability() {
        boolean hasMapTools = toolCategories.contains("map_navigation");
        boolean hasSearchTools = toolCategories.contains("information");
        boolean hasXiaohongshuTools = toolCategories.contains("xiaohongshu");
        String desc = "Handles dialogue, writing, analysis, vision-language responses, and tool-assisted runtime tasks.";
        if (!toolCategories.isEmpty()) {
            desc += " Internal tool categories: " + toolCategories + ".";
        }
        if (hasMapTools) {
            desc += " Can search places, find nearby POIs, plan driving/walking/transit/bicycling routes, geocode addresses, and generate static maps via Amap (高德地图) tools.";
        }
        if (hasSearchTools) {
            desc += " Can search the internet for real-time news, facts, and web content.";
        }
        boolean hasAutomationTools = toolCategories.contains("automation");
        if (hasAutomationTools) {
            desc += " Can create/manage reminders, timers, alarms, recurring reminders, and schedule items.";
        }
        if (hasXiaohongshuTools) {
            desc += " Can search Xiaohongshu notes, read note details/comments, view user profiles, comment/reply, like/favorite, and publish image/video notes via MCP tools.";
        }
        List<String> strengths = new ArrayList<>(List.of("dialogue", "writing", "analysis", "vision", "runtime-tools"));
        if (hasMapTools) {
            strengths.addAll(List.of("place-search", "nearby-search", "route-planning", "map-navigation", "geocoding"));
        }
        if (hasSearchTools) {
            strengths.add("web-search");
        }
        if (hasAutomationTools) {
            strengths.addAll(List.of("reminder", "timer", "alarm", "schedule", "recurring-reminder"));
        }
        if (hasXiaohongshuTools) {
            strengths.addAll(List.of("xiaohongshu-search", "xiaohongshu-publish", "xiaohongshu-social"));
        }
        // Build routing keywords dynamically based on available tools
        List<String> routingKeywords = new ArrayList<>();
        if (hasXiaohongshuTools) {
            routingKeywords.addAll(List.of("小红书", "笔记", "博主", "点赞", "收藏", "评论"));
        }
        return new AgentCapability(
                "chat-generation",
                desc,
                strengths,
                "text",
                routingKeywords
        );
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        log.info("ChatAgent executing task: instruction={}", task.instruction());

        String userId = stringParam(task.parameters(), "userId");
        List<String> imageUrls = task.executionContext() != null
                ? task.executionContext().imageUrls()
                : stringList(task.parameters().get("imageUrls"));
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(userId, task.instruction());
        ContextPackage context = AgentContextAssembler.build(
                contextEngineeringService, task, effectiveSystemPrompt, agentMemory);

        String response;
        var pendingDrainRef = new AtomicReference<UserMessageTool.PendingUserMessage>();
        var screenshotsDrainRef = new AtomicReference<List<byte[]>>();
        var transcriptReportRef = new AtomicReference<ToolLoopContextRuntime.Report>();

        if (canUseToolLoop()) {
            try {
                response = chatWithTools(userId, context, imageUrls,
                        pendingDrainRef, screenshotsDrainRef, transcriptReportRef);
            } catch (RuntimeException e) {
                log.warn("ChatAgent tool loop failed: {}", e.getMessage());
                if (chatClient != null) {
                    log.info("Falling back to legacy chat client (no tools)");
                    response = fallbackChat(context, imageUrls);
                } else {
                    return AgentResult.failed(task.taskId(), "对话服务暂不可用：" + e.getMessage());
                }
            }
        } else {
            response = fallbackChat(context, imageUrls);
        }

        // Detect PAUSED signal from LLM output, or force PAUSED if pending images exist
        UserMessageTool.PendingUserMessage pending = pendingDrainRef.get();
        List<byte[]> drainedScreenshots = screenshotsDrainRef.get() != null
                ? screenshotsDrainRef.get() : List.of();

        boolean hasPendingImages = (pending != null && !pending.images().isEmpty())
                || !drainedScreenshots.isEmpty();
        boolean isPaused = response != null && response.startsWith("__PAUSED__:");

        if (isPaused || hasPendingImages) {
            String pausedText = isPaused
                    ? response.substring("__PAUSED__:".length()).trim()
                    : "";
            String effectiveMessage;
            List<byte[]> allImages = new ArrayList<>();

            if (pending != null) {
                effectiveMessage = !pending.text().isBlank() ? pending.text() : pausedText;
                allImages.addAll(pending.images());
            } else {
                effectiveMessage = pausedText;
            }
            allImages.addAll(drainedScreenshots);

            List<ModelReply.ImagePayload> imagePayloads = new ArrayList<>();
            for (int i = 0; i < allImages.size(); i++) {
                imagePayloads.add(new ModelReply.ImagePayload(allImages.get(i),
                        "screenshot_" + (i + 1) + ".png"));
            }

            log.info("ChatAgent PAUSED: message={}, images={}", effectiveMessage, imagePayloads.size());
            ToolLoopContextRuntime.Report report = transcriptReportRef.get();
            return AgentResult.paused(task.taskId(), effectiveMessage,
                    report != null ? report.resumeState() : Map.of(), imagePayloads);
        }

        String motouGifPath = MotouTool.getAndClearLastGifPath();
        if (motouGifPath != null && (response == null || !response.contains("[MOTOU_GIF:"))) {
            response = "[MOTOU_GIF:" + motouGifPath + "]\n" + (response != null ? response : "");
        }

        LocalFileTools.PreparedFile localFile = LocalFileTools.peekPreparedFile();
        if (localFile != null && (response == null || !response.contains("[LOCAL_FILE:"))) {
            response = "[LOCAL_FILE:" + localFile.absolutePath() + "]\n" + (response != null ? response : "");
        }

        log.info("ChatAgent response: {} chars", response != null ? response.length() : 0);
        Map<String, String> signals = buildSignals(response);
        if (transcriptReportRef.get() != null) {
            signals.putAll(transcriptReportRef.get().signals());
        }
        return AgentResult.success(task.taskId(), response, response, signals);
    }

    private String fallbackChat(ContextPackage context, List<String> imageUrls) {
        long timeoutSeconds = agentProperties != null
                ? Math.max(30, agentProperties.getToolCallTimeoutSeconds() / 2) : 60;

        var executor = Executors.newSingleThreadExecutor();
        AgentContextAssembler.LegacyCall call = AgentContextAssembler.toLegacyCall(context);
        Future<String> future = executor.submit(() -> chatClient.chat(
                call.userMessage(), imageUrls, call.history(), call.systemPrompt()));

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Fallback chat timed out after {}s", timeoutSeconds);
            return "抱歉，AI 服务响应超时，请稍后重试。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return "抱歉，AI 服务请求被中断，请稍后重试。";
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause();
            log.warn("Fallback chat failed: {}", cause != null ? cause.getMessage() : "unknown");
            return "抱歉，AI 服务暂时不可用（" + (cause != null ? cause.getMessage() : "未知错误") + "），请稍后重试。";
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean canUseToolLoop() {
        return toolChatClient != null;
    }

    private String chatWithTools(String userId, ContextPackage context, List<String> imageUrls,
                                  AtomicReference<UserMessageTool.PendingUserMessage> pendingRef,
                                  AtomicReference<List<byte[]>> screenshotsRef,
                                  AtomicReference<ToolLoopContextRuntime.Report> transcriptReportRef) {
        // Hard safety cap — per-round timeout is handled by TimeoutChatModel.
        // This only triggers if LLM enters an infinite tool loop.
        final long HARD_CAP_SECONDS = 600;

        var executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            AutomationRuntime.setCurrentUser(userId);
            RagTools.setCurrentUser(userId);
            DiDiTaxiTools.setCurrentUser(userId);
            SkillTools.setCurrentAgent(getName());
            try {
                String resp = toolChatClient.prompt()
                        .messages(AgentContextAssembler.toSpringMessages(context, imageUrls))
                        .toolContext(Map.of("imageBase64Urls", imageUrls != null ? imageUrls : List.of()))
                        .call()
                        .content();
                pendingRef.set(UserMessageTool.drain());
                screenshotsRef.set(BrowserTools.drainScreenshots());
                return resp;
            } finally {
                transcriptReportRef.set(ToolLoopContextRuntime.drain());
                SkillTools.clearCurrentAgent();
                AutomationRuntime.clearCurrentUser();
                RagTools.clearCurrentUser();
                DiDiTaxiTools.clearCurrentUser();
            }
        });

        try {
            return future.get(HARD_CAP_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("ChatAgent hard cap triggered after {}s — possible infinite tool loop", HARD_CAP_SECONDS);
            throw new RuntimeException("工具调用超时（" + HARD_CAP_SECONDS + "秒硬限制），可能是任务过于复杂或进入循环，请简化任务后重试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("工具调用被中断");
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause != null ? cause : e);
        } finally {
            executor.shutdownNow();
        }
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

    /** Build compact structured signals from the agent output for downstream context injection. */
    private static Map<String, String> buildSignals(String response) {
        Map<String, String> signals = new LinkedHashMap<>();
        if (response == null || response.isBlank()) {
            return signals;
        }
        // Detect file output
        if (response.contains("[FILE:") && response.contains("[/FILE]")) {
            signals.put("has_file_output", "true");
        }
        // Content length bracket (compact indicator for downstream agents)
        int len = response.length();
        if (len < 500) {
            signals.put("content_length", "short");
        } else if (len < 2000) {
            signals.put("content_length", "medium");
        } else {
            signals.put("content_length", "long");
        }
        // Check for markdown headings as content structure signal
        if (response.contains("\n#") || response.startsWith("#")) {
            signals.put("has_structure", "true");
        }
        return signals;
    }

    private String buildEffectiveSystemPrompt(String userId, String instruction) {
        StringBuilder sb = new StringBuilder();
        String systemPrompt = agentProperties != null ? agentProperties.getSystemPrompt() : null;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append(systemPrompt);
        }
        if (!skillsSummary.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(skillsSummary);
        }
        return sb.toString();
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object val = params != null ? params.get(key) : null;
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
