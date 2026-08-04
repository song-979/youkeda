package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestrationResult;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestratorAgent;
import com.youkeda.project.wechatproject.bot.orchestrator.OrchestratorProperties;
import com.youkeda.project.wechatproject.bot.orchestrator.TaskScratchpad;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceCatalog;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceProfile;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapDirectionTools;
import com.youkeda.project.wechatproject.bot.tool.travel.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationRuntime;
import com.youkeda.project.wechatproject.bot.tool.chat.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.chat.RagTools;
import com.youkeda.project.wechatproject.bot.tool.chat.ScheduledTaskExecutionRequest;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
    private static final Pattern FILE_MARKER = Pattern.compile(
            "\\[FILE:(.+?)]\\r?\\n(.*?)\\r?\\n\\[/FILE]", Pattern.DOTALL);
    private static final Pattern MOTOU_GIF_MARKER = Pattern.compile(
            "\\[MOTOU_GIF:(.+?)]");
    private static final Pattern LOCAL_FILE_MARKER = Pattern.compile(
            "\\[LOCAL_FILE:(.+?)]");

    private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> userSimpleMode = new ConcurrentHashMap<>();
    private final OrchestratorAgent orchestrator;
    private final AgentRegistry registry;
    private final ConversationMemory memory;
    private final VoiceCatalog voiceCatalog;
    private final DocumentService documentService;
    private final int maxLoops;
    private final boolean clarificationEnabled;
    private final boolean reflectionEnabled;
    private final SimpleModeRouter simpleModeRouter;
    private final IntentRouter intentRouter;

    public MessageRouter(OrchestratorAgent orchestrator, AgentRegistry registry, ConversationMemory memory,
                         VoiceCatalog voiceCatalog, DocumentService documentService,
                         OrchestratorProperties orchestratorProperties,
                         SimpleModeRouter simpleModeRouter) {
        this(orchestrator, registry, memory, voiceCatalog, documentService, orchestratorProperties,
                simpleModeRouter, null);
    }

    public MessageRouter(OrchestratorAgent orchestrator, AgentRegistry registry, ConversationMemory memory,
                         VoiceCatalog voiceCatalog, DocumentService documentService,
                         OrchestratorProperties orchestratorProperties,
                         SimpleModeRouter simpleModeRouter,
                         IntentRouter intentRouter) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.memory = memory;
        this.voiceCatalog = voiceCatalog;
        this.documentService = documentService;
        this.maxLoops = Math.max(1, orchestratorProperties.getMaxLoops());
        this.clarificationEnabled = orchestratorProperties.isClarificationEnabled();
        this.reflectionEnabled = orchestratorProperties.isReflectionEnabled();
        this.simpleModeRouter = simpleModeRouter;
        this.intentRouter = intentRouter;
    }

    public ModelReply route(String userId, String text, List<String> imageBase64Urls) throws IOException {
        // Fair lock: with async message handling (virtual threads) several worker threads
        // can arrive concurrently; fairness keeps replies roughly in arrival order.
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock(true));
        lock.lock();
        DiDiTaxiTools.setCurrentUser(userId);
        RagTools.setCurrentUser(userId);
        AutomationRuntime.setCurrentUser(userId);
        try {
            // Mode toggle commands
            if (text != null && text.trim().equals("/easy")) {
                userSimpleMode.put(userId, true);
                log.info("user {} switched to simple mode", userId);
                return ModelReply.text("已切换到简单模式。直接告诉我你想做什么吧~");
            }
            if (text != null && text.trim().equals("/normal")) {
                userSimpleMode.remove(userId);
                log.info("user {} switched to long-task mode", userId);
                return ModelReply.text("已切换到长任务模式。我可以处理复杂的多步骤任务。");
            }

            // Simple mode: keyword → single agent, no orchestrator
            if (userSimpleMode.getOrDefault(userId, false)) {
                return simpleModeRouter.route(userId, text, imageBase64Urls);
            }

        List<ChatRequest.Message> history = memory != null ? memory.getHistory(userId, text) : List.of();
        ImageMemory imageMemory = imageBase64Urls.isEmpty()
                ? resolveImageMemory(userId)
                : ImageMemory.empty();

        if (!imageBase64Urls.isEmpty() && memory != null) {
            memory.rememberImageContext(userId, imageBase64Urls, text);
        }

        // Check for saved scratchpad from a previous PAUSED turn
        TaskScratchpad restoredScratchpad = null;
        String effectiveText = text;
        if (memory != null) {
            String savedJson = memory.loadScratchpad(userId);
            if (savedJson != null && !savedJson.isBlank()) {
                restoredScratchpad = TaskScratchpad.fromJson(savedJson);
                memory.clearScratchpad(userId);
                if (!restoredScratchpad.isEmpty()) {
                    effectiveText = restoredScratchpad.toResumePrompt()
                            + "\n【用户消息】\n" + (text != null ? text : "");
                    log.info("resumed scratchpad with {} prior record(s)", restoredScratchpad.records().size());
                }
            }
        }

        UserRequest request = new UserRequest(
                userId,
                effectiveText,
                imageBase64Urls,
                history,
                imageMemory.imageUrls(),
                imageMemory.summary());

        OrchestrationResult result = specialCasePlan(request);
        if (result == null && intentRouter != null) {
            // L1/L2 lightweight routing: chitchat and unambiguous single-domain requests
            // skip the plan LLM call entirely.
            result = intentRouter.tryDirectRoute(request);
        }
        if (result == null) {
            result = orchestrator.plan(request);
        }

        // If we have a restored scratchpad and the plan produced tasks, prepend completed
        // records into the new scratchpad so reflect/PAUSED-check can see full history
        if (restoredScratchpad != null && !restoredScratchpad.isEmpty()
                && result.status() == OrchestrationResult.Status.EXECUTE) {
            for (TaskScratchpad.ExecutionRecord r : restoredScratchpad.records()) {
                result.scratchpad().record(r.task(), r.result());
            }
        }

        log.info("orchestrator plan: status={}, reasoning={}", result.status(), result.reasoning());

        if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
            if (clarificationEnabled) {
                String question = result.clarificationQuestion();
                if (memory != null) {
                    memory.append(userId, text, question);
                }
                return ModelReply.text(question);
            }
            result = chatOnlyPlan(text, result.scratchpad());
        }

        if (result.status() == OrchestrationResult.Status.COMPLETED) {
            ModelReply finalReply = result.finalReply() != null ? result.finalReply() : ModelReply.text("completed");
            persistMemory(userId, text, result, finalReply);
            return finalReply;
        }

        int loops = 0;
        while (result.status() == OrchestrationResult.Status.EXECUTE && loops < maxLoops) {
            loops++;

            for (AgentTask task : result.tasks()) {
                AgentTask executableTask = hydrateTask(request, result.scratchpad(), task)
                        .withUserId(userId);
                try {
                    AgentUnit worker = registry.get(executableTask.agentType());
                    SkillTools.setCurrentAgent(executableTask.agentType());
                    AgentResult agentResult;
                    try {
                        agentResult = worker.execute(executableTask);
                    } finally {
                        SkillTools.clearCurrentAgent();
                    }
                    result.scratchpad().record(executableTask, agentResult);
                    // PAUSED: save scratchpad and return immediately — no more tasks this turn
                    if (agentResult.isPaused()) {
                        if (memory != null) {
                            memory.saveScratchpad(userId, result.scratchpad().toJson());
                        }
                        String message = agentResult.messageToUser() != null
                                ? agentResult.messageToUser() : "请提供更多信息以继续。";
                        log.info("task paused: agent={}, taskId={}, message={}",
                                executableTask.agentType(), executableTask.taskId(),
                                message.substring(0, Math.min(80, message.length())));

                        List<ModelReply.ImagePayload> pausedImages = agentResult.pausedImages();
                        if (!pausedImages.isEmpty()) {
                            return ModelReply.mixed(message, pausedImages);
                        }
                        return ModelReply.text(message);
                    }
                    log.info("task executed: agent={}, status={}, taskId={}",
                            executableTask.agentType(), agentResult.status(), executableTask.taskId());
                } catch (Exception e) {
                    log.error("task execution failed: agent={}, taskId={}, error={}",
                            executableTask.agentType(), executableTask.taskId(), e.getMessage());
                    result.scratchpad().record(executableTask,
                            AgentResult.failed(executableTask.taskId(), e.getMessage()));
                }
            }

            if (!reflectionEnabled || result.skipReflection()) {
                break;
            }

            result = orchestrator.reflect(result.scratchpad(), request);
            log.info("orchestrator reflect: status={}, reasoning={}", result.status(), result.reasoning());

            if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
                if (clarificationEnabled) {
                    if (memory != null) {
                        memory.append(userId, text, result.clarificationQuestion());
                    }
                    return ModelReply.text(result.clarificationQuestion());
                }
                result = chatOnlyPlan(text, result.scratchpad());
            }
        }

        if (loops >= maxLoops && reflectionEnabled) {
            log.warn("orchestrator hit max loops ({}), forcing completion", maxLoops);
        }

        // Normal completion — clear any saved scratchpad
        if (memory != null) {
            memory.clearScratchpad(userId);
        }

        ModelReply finalReply = buildFinalReply(result);
        persistMemory(userId, text, result, finalReply);
        return finalReply;
        } finally {
            DiDiTaxiTools.clearCurrentUser();
            RagTools.clearCurrentUser();
            AutomationRuntime.clearCurrentUser();
            UserMessageTool.clear();
            lock.unlock();
        }
    }

    public ModelReply routeScheduledTask(ScheduledTaskExecutionRequest scheduledRequest) throws IOException {
        String userId = scheduledRequest.recipientId();
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock(true));
        lock.lock();
        // Scheduled tasks run on scheduler threads, not message threads — the per-user
        // tool context must be installed explicitly here or RAG/DiDi/automation tools
        // would see no current user.
        DiDiTaxiTools.setCurrentUser(userId);
        RagTools.setCurrentUser(userId);
        AutomationRuntime.setCurrentUser(userId);
        try {
            LocalFileTools.getAndClearPreparedFile();
            String text = scheduledTaskPrompt(scheduledRequest);
            List<ChatRequest.Message> history = memory != null ? memory.getHistory(userId, text) : List.of();
            UserRequest request = new UserRequest(
                    userId,
                    text,
                    List.of(),
                    history,
                    List.of(),
                    null);

            OrchestrationResult result = orchestrator.plan(request);
            log.info("scheduled task orchestrator plan: status={}, reasoning={}",
                    result.status(), result.reasoning());

            if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
                throw new IOException(result.clarificationQuestion() != null
                        ? result.clarificationQuestion()
                        : "scheduled task needs clarification");
            }

            if (result.status() == OrchestrationResult.Status.COMPLETED) {
                return result.finalReply() != null ? result.finalReply() : ModelReply.text("completed");
            }

            int loops = 0;
            while (result.status() == OrchestrationResult.Status.EXECUTE && loops < maxLoops) {
                loops++;

                for (AgentTask task : result.tasks()) {
                    AgentTask executableTask = hydrateTask(request, result.scratchpad(), task)
                            .withUserId(userId);
                    try {
                        AgentUnit worker = registry.get(executableTask.agentType());
                        SkillTools.setCurrentAgent(executableTask.agentType());
                        AgentResult agentResult;
                        try {
                            agentResult = worker.execute(executableTask);
                        } finally {
                            SkillTools.clearCurrentAgent();
                        }
                        result.scratchpad().record(executableTask, agentResult);
                        log.info("scheduled task executed: agent={}, status={}, taskId={}",
                                executableTask.agentType(), agentResult.status(), executableTask.taskId());
                    } catch (Exception e) {
                        log.error("scheduled task execution failed: agent={}, taskId={}, error={}",
                                executableTask.agentType(), executableTask.taskId(), e.getMessage());
                        result.scratchpad().record(executableTask,
                                AgentResult.failed(executableTask.taskId(), e.getMessage()));
                    }
                }

                if (!reflectionEnabled) {
                    break;
                }

                result = orchestrator.reflect(result.scratchpad(), request);
                log.info("scheduled task orchestrator reflect: status={}, reasoning={}",
                        result.status(), result.reasoning());

                if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
                    throw new IOException(result.clarificationQuestion() != null
                            ? result.clarificationQuestion()
                            : "scheduled task needs clarification");
                }
            }

            return buildFinalReply(result);
        } finally {
            DiDiTaxiTools.clearCurrentUser();
            RagTools.clearCurrentUser();
            AutomationRuntime.clearCurrentUser();
            UserMessageTool.clear();
            lock.unlock();
        }
    }

    private OrchestrationResult specialCasePlan(UserRequest request) {
        String text = request.text() == null ? "" : request.text().trim();

        // 用户只发了图片没有附带文字，追问用户具体需求
        if (!request.imageBase64Urls().isEmpty() && text.isEmpty()) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.NEEDS_CLARIFICATION)
                    .reasoning("user sent images without text, need to ask for requirements")
                    .clarificationQuestion("你发送了图片，请问需要我做些什么呢？")
                    .build();
        }

        if (registry.contains("SPEECH_GEN") && isComfortStoryRequest(text)) {
            String gentleVoice = voiceCatalog.findByMood("comforting")
                    .map(VoiceProfile::voiceId)
                    .orElse("longanhuan_v3.6");
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("comfort story voice flow")
                    .tasks(List.of(
                            new AgentTask(
                                    "CHAT",
                                    "Write a gentle and comforting short story in Chinese for someone who feels sad. Output only the story body.",
                                    Map.of("flow", "comfort-story")),
                            new AgentTask(
                                    "SPEECH_GEN",
                                    "{{LAST_CHAT_TEXT}}",
                                    Map.of("source", "LAST_CHAT_TEXT", "voice", gentleVoice,
                                            "instruction", "用温暖、轻柔、舒缓的语气朗读，像是在哄一个难过的朋友入睡"))
                    ))
                    .build();
        }

        if (registry.contains("SPEECH_GEN") && isGenerateCopyAndSpeakRequest(text)) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("generate copywriting first, then read it aloud")
                    .tasks(List.of(
                            new AgentTask(
                                    "CHAT",
                                    "Based on the user's current request and conversation history, write one concise Chinese promotional line that matches the requested topic. Output only the final promotional line with no explanation or extra formatting.",
                                    Map.of("flow", "copy-then-speech")),
                            new AgentTask(
                                    "SPEECH_GEN",
                                    "{{LAST_CHAT_TEXT}}",
                                    Map.of("source", "LAST_CHAT_TEXT"))
                    ))
                    .build();
        }

        if (registry.contains("BROWSER") && isLoginConfirmation(text)) {
            String pendingIntent = extractLastUserIntent(request.history());
            String lastChatOutput = extractLastChatOutput(request.history());

            StringBuilder instruction = new StringBuilder();
            instruction.append("请完成以下任务：\n");
            instruction.append(pendingIntent != null ? pendingIntent : "（从对话历史中确定用户意图）");

            // Only include lastChatOutput if it's substantial content (not just a short status)
            if (lastChatOutput != null && !lastChatOutput.isBlank()
                    && lastChatOutput.length() > 50) {
                instruction.append("\n\n【参考内容（如有需要请使用）】\n");
                if (lastChatOutput.length() > 3000) {
                    instruction.append(lastChatOutput, 0, 3000).append("\n...（内容已截断）");
                } else {
                    instruction.append(lastChatOutput);
                }
            }

            instruction.append("\n\n执行步骤：");
            instruction.append("\n1. 首先用 browser_snapshot 确认当前已登录成功。如果未登录，报告错误。");
            instruction.append("\n2. 登录确认后立即执行上述任务，包括：打开页面、填写内容、提交发布等所有必要步骤。");
            instruction.append("\n3. 任务完成后用 browser_snapshot 确认最终状态。");
            instruction.append("\n\n注意：必须完成全部步骤，不要完成第1步就停止。");

            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("user confirmed login, route to browser to verify and continue pending task")
                    .tasks(List.of(
                            new AgentTask(
                                    "BROWSER",
                                    instruction.toString(),
                                    Map.of("flow", "login-verification"))
                    ))
                    .build();
        }

        if (registry.contains("IMAGE_GEN") && isImageGenerateAndDescribeRequest(text)) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("image generation then multimodal description")
                    .tasks(List.of(
                            new AgentTask(
                                    "IMAGE_GEN",
                                    "Create an image based on this user request: " + text,
                                    Map.of("flow", "image-then-describe")),
                            new AgentTask(
                                    "CHAT",
                                    "Look at the newly generated image and answer in Chinese with a detailed description plus one follow-up improvement suggestion.",
                                    Map.of("use_latest_image", true))
                    ))
                    .build();
        }

        return null;
    }

    private static String scheduledTaskPrompt(ScheduledTaskExecutionRequest request) {
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                ZonedDateTime.now(ZoneId.systemDefault()));
        StringBuilder sb = new StringBuilder();
        sb.append("[scheduled-task-trigger]\n");
        sb.append("Current application time: ").append(now).append("\n");
        sb.append("The configured trigger time has arrived. Execute the saved user task now.\n\n");
        sb.append("Task id: ").append(request.taskId()).append("\n");
        sb.append("Title: ").append(request.title()).append("\n");
        sb.append("Scheduled for: ").append(request.scheduledFor()).append("\n");
        if (request.originalRequest() != null && !request.originalRequest().isBlank()) {
            sb.append("Original user request: ").append(request.originalRequest()).append("\n");
        }
        if (!request.expectedToolCategories().isEmpty()) {
            sb.append("Expected tool categories for audit only, not a restriction: ")
                    .append(String.join(", ", request.expectedToolCategories()))
                    .append("\n");
        }
        sb.append("\nSaved instruction to execute now:\n");
        sb.append(request.instruction()).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- Execute the saved instruction now. Do not report stale data captured at creation time.\n");
        sb.append("- You may use any available tools needed to satisfy the instruction.\n");
        sb.append("- Do not create, update, or delete automation unless the saved instruction explicitly asks for automation management.\n");
        sb.append("- Do not say you will do it later; this is already the trigger moment.\n");
        sb.append("- If required information is missing, return a concise failure reason instead of asking the user a clarification question.\n");
        return sb.toString();
    }

    private static OrchestrationResult chatOnlyPlan(String text, TaskScratchpad scratchpad) {
        return OrchestrationResult.builder()
                .status(OrchestrationResult.Status.EXECUTE)
                .reasoning("clarification disabled: fallback to chat")
                .tasks(List.of(new AgentTask("CHAT", text != null ? text : "", Map.of())))
                .scratchpad(scratchpad)
                .build();
    }

    private AgentTask hydrateTask(UserRequest request, TaskScratchpad scratchpad, AgentTask task) {
        Map<String, Object> params = new LinkedHashMap<>(task.parameters());

        if ("CHAT".equals(task.agentType())) {
            List<String> imageUrls = new ArrayList<>();
            imageUrls.addAll(request.imageBase64Urls());
            imageUrls.addAll(request.rememberedImageBase64Urls());
            imageUrls.addAll(scratchpad.successfulImageDataUrls());

            if (!imageUrls.isEmpty()) {
                params.put("imageUrls", distinct(imageUrls));
            }

            List<ChatRequest.Message> taskHistory = new ArrayList<>(request.history());
            if (request.rememberedImageSummary() != null && !request.rememberedImageSummary().isBlank()) {
                taskHistory.add(new ChatRequest.Message("assistant",
                        "[remembered-image-summary] " + request.rememberedImageSummary()));
            }
            if (!taskHistory.isEmpty()) {
                params.put("history", taskHistory);
            }
        }

        if ("SPEECH_GEN".equals(task.agentType())) {
            params.put("text", resolveSpeechText(task, scratchpad));
        }

        if ("BROWSER".equals(task.agentType()) && task.instruction().contains("{{LAST_CHAT_TEXT}}")) {
            String lastChatText = scratchpad.lastSuccessfulChatText();
            if (lastChatText != null && !lastChatText.isBlank() && !isLikelyErrorText(lastChatText)) {
                return task.withInstruction(task.instruction().replace("{{LAST_CHAT_TEXT}}", lastChatText))
                        .withParameters(params);
            }
            if (lastChatText != null && isLikelyErrorText(lastChatText)) {
                log.warn("refusing to inject error-like CHAT output into BROWSER task: {}",
                        lastChatText.length() > 100 ? lastChatText.substring(0, 100) + "..." : lastChatText);
            }
        }

        // Hard protection: if BROWSER instruction is very long, the orchestrator likely embedded full
        // content instead of using {{LAST_CHAT_TEXT}}. Truncate and try to inject CHAT output instead.
        if ("BROWSER".equals(task.agentType()) && task.instruction().length() > 1500) {
            log.warn("BROWSER instruction is {} chars — orchestrator likely embedded content. Truncating.",
                    task.instruction().length());
            String lastChatText = scratchpad.lastSuccessfulChatText();
            if (lastChatText != null && !lastChatText.isBlank() && !isLikelyErrorText(lastChatText)) {
                // Reconstruct: keep first 500 chars of instruction (URL/login/first steps), append CHAT content
                String prefix = task.instruction().substring(0, Math.min(500, task.instruction().length()));
                String reconstructed = prefix + "\n\n---\n请将以下内容写入文档正文中：\n" + lastChatText;
                return task.withInstruction(reconstructed).withParameters(params);
            }
            // No good CHAT text available — truncate the instruction to avoid crushing the LLM
            return task.withInstruction(
                    task.instruction().substring(0, Math.min(1500, task.instruction().length()))
                            + "\n...[指令过长已截断，请根据上下文推断任务]")
                    .withParameters(params);
        }

        return task.withParameters(params);
    }

    private static String resolveSpeechText(AgentTask task, TaskScratchpad scratchpad) {
        String text = stringValue(task.parameters().get("text"));
        if (text == null || text.isBlank()) {
            text = task.instruction();
        }

        String source = stringValue(task.parameters().get("source"));
        if ("LAST_CHAT_TEXT".equalsIgnoreCase(source)) {
            String chatText = scratchpad.lastSuccessfulChatText();
            if (chatText != null && !chatText.isBlank()) {
                return chatText;
            }
        }

        String placeholder = "{{LAST_CHAT_TEXT}}";
        if (text != null && text.contains(placeholder)) {
            String chatText = scratchpad.lastSuccessfulChatText();
            if (chatText != null && !chatText.isBlank()) {
                return text.replace(placeholder, chatText);
            }
        }

        return text;
    }

    private void persistMemory(String userId, String userText, OrchestrationResult result, ModelReply finalReply) {
        if (memory == null) {
            return;
        }

        String assistantText = determineMemoryText(result, finalReply);
        memory.append(userId, userText, assistantText != null ? assistantText : "");

        List<String> imageUrls = result.scratchpad().successfulImageDataUrls();
        if (!imageUrls.isEmpty()) {
            String summary = determineImageSummary(result, finalReply);
            memory.rememberImageContext(userId, imageUrls, summary);
        }
    }

    private String determineMemoryText(OrchestrationResult result, ModelReply finalReply) {
        // Concatenate ALL successful CHAT outputs (multi-task requests produce multiple CHAT results)
        List<String> allChatTexts = result.scratchpad().allSuccessfulChatTexts();
        if (!allChatTexts.isEmpty()) {
            return String.join("\n\n---\n\n", allChatTexts);
        }

        if (finalReply != null && finalReply.getTextContent() != null && !finalReply.getTextContent().isBlank()) {
            return finalReply.getTextContent();
        }

        String imageSummary = result.scratchpad().lastSuccessfulImageSummary();
        if (imageSummary != null && !imageSummary.isBlank()) {
            return imageSummary;
        }

        if (finalReply != null && finalReply.getType() == ModelReply.Type.VOICE) {
            return "[voice-generated]";
        }

        if (finalReply != null && finalReply.getType() == ModelReply.Type.IMAGE) {
            return "[image-generated]";
        }

        return null;
    }

    private String determineImageSummary(OrchestrationResult result, ModelReply finalReply) {
        String chatText = result.scratchpad().lastSuccessfulChatText();
        if (chatText != null && !chatText.isBlank()) {
            return chatText;
        }

        String imageSummary = result.scratchpad().lastSuccessfulImageSummary();
        if (imageSummary != null && !imageSummary.isBlank()) {
            return imageSummary;
        }

        if (finalReply != null && finalReply.getTextContent() != null && !finalReply.getTextContent().isBlank()) {
            return finalReply.getTextContent();
        }

        return "generated image";
    }

    private ImageMemory resolveImageMemory(String userId) {
        if (memory == null) {
            return ImageMemory.empty();
        }
        List<String> imageUrls = memory.getLatestImageDataUrls(userId);
        return ImageMemory.of(imageUrls, memory.getLatestImageSummary(userId));
    }

    /**
     * Paths embedded in LLM output markers (e.g. [MOTOU_GIF:path]) must stay inside
     * well-known writable roots: the application directory, the user home, or the
     * system temp dir. Anything else is refused so a crafted model response cannot
     * make the bot read and exfiltrate arbitrary local files.
     */
    private static boolean isAllowedOutboundPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            List<Path> allowedRoots = List.of(
                    Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                    Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
                    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
            for (Path root : allowedRoots) {
                if (path.startsWith(root)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private ModelReply buildFinalReply(OrchestrationResult result) {
        TaskScratchpad scratchpad = result.scratchpad();
        String textReply = scratchpad.lastSuccessfulChatText();
        if (textReply == null || textReply.isBlank()) {
            textReply = result.finalReply() != null ? result.finalReply().getTextContent() : null;
        }

        ModelReply.FilePayload filePayload = null;
        LocalFileTools.PreparedFile preparedLocalFile = LocalFileTools.getAndClearPreparedFile();
        if (preparedLocalFile != null) {
            filePayload = new ModelReply.FilePayload(preparedLocalFile.bytes(), preparedLocalFile.fileName());
            if (textReply != null) {
                textReply = LOCAL_FILE_MARKER.matcher(textReply).replaceAll("").trim();
            }
            log.info("loaded local file payload from path={}, size={} bytes",
                    preparedLocalFile.absolutePath(), preparedLocalFile.bytes().length);
        }

        if (filePayload == null && textReply != null) {
            ParsedFileResult parsed = extractFileMarkers(textReply);
            if (parsed != null) {
                textReply = parsed.remainderText();
                DocumentService.GeneratedFile genFile = documentService.generate(parsed.fileContent(), parsed.fileName());
                filePayload = new ModelReply.FilePayload(genFile.bytes(), genFile.fileName());
            }
        }

        List<ModelReply.ImagePayload> images = new ArrayList<>();
        if (textReply != null) {
            Matcher motouMatcher = MOTOU_GIF_MARKER.matcher(textReply);
            if (motouMatcher.find()) {
                String gifPath = motouMatcher.group(1).trim();
                // The marker path originates from LLM output — validate it against a
                // whitelist of writable roots before reading anything from disk.
                if (!isAllowedOutboundPath(gifPath)) {
                    log.warn("refusing to read MOTOU_GIF outside allowed roots: {}", gifPath);
                } else {
                    try {
                        byte[] gifBytes = Files.readAllBytes(Path.of(gifPath));
                        filePayload = new ModelReply.FilePayload(gifBytes, "motou.gif");
                        textReply = motouMatcher.replaceFirst("").trim();
                        log.info("loaded MOTOU_GIF from path={}, size={} bytes, will send as file", gifPath, gifBytes.length);
                    } catch (IOException e) {
                        log.error("failed to read MOTOU_GIF from path={}", gifPath, e);
                    }
                }
            }
        }
        ModelReply.AudioPayload audio = null;

        for (TaskScratchpad.ExecutionRecord record : scratchpad.records()) {
            if (record.result().status() != AgentResult.Status.SUCCESS) {
                continue;
            }

            Object output = record.result().output();
            if (output == null) {
                continue;
            }

            if (output instanceof GeneratedImage generatedImage && "IMAGE_GEN".equals(record.task().agentType())) {
                images.add(new ModelReply.ImagePayload(generatedImage.bytes(), generatedImage.fileName()));
            } else if (output instanceof byte[] imgBytes && "IMAGE_GEN".equals(record.task().agentType())) {
                images.add(new ModelReply.ImagePayload(imgBytes, "generated.png"));
            } else if (output instanceof SpeechAgent.TtsOutput ttsOutput) {
                audio = new ModelReply.AudioPayload(
                        ttsOutput.audioBytes(), ttsOutput.format(),
                        ttsOutput.durationMs(), ttsOutput.sampleRate());
            }
        }

        // Drain static map images generated by Amap tools (around search / direction)
        for (byte[] imgBytes : AmapAroundSearchTools.drainMapImages()) {
            images.add(new ModelReply.ImagePayload(imgBytes, "amap_around.png"));
        }
        for (byte[] imgBytes : AmapDirectionTools.drainMapImages()) {
            images.add(new ModelReply.ImagePayload(imgBytes, "amap_route.png"));
        }

        if (filePayload != null && audio != null && !images.isEmpty() && textReply != null && !textReply.isBlank()) {
            return new ModelReply(ModelReply.Type.MIXED, textReply, images, audio, filePayload);
        }
        if (filePayload != null && audio != null && !images.isEmpty()) {
            return new ModelReply(ModelReply.Type.MIXED, null, images, audio, filePayload);
        }
        if (filePayload != null && audio != null) {
            return new ModelReply(ModelReply.Type.MIXED, textReply, List.of(), audio, filePayload);
        }

        if (filePayload != null && !images.isEmpty() && textReply != null && !textReply.isBlank()) {
            return ModelReply.mixedWithFile(textReply, images, filePayload);
        }
        if (filePayload != null && !images.isEmpty()) {
            return ModelReply.mixedWithFile(null, images, filePayload);
        }
        if (filePayload != null && textReply != null && !textReply.isBlank() && images.isEmpty() && audio == null) {
            return ModelReply.mixedWithFile(textReply, List.of(), filePayload);
        }
        if (filePayload != null) {
            return ModelReply.file(filePayload.bytes(), filePayload.fileName());
        }

        if (!images.isEmpty() && audio != null && textReply != null && !textReply.isBlank()) {
            return new ModelReply(ModelReply.Type.MIXED, textReply, images, audio, filePayload);
        }
        if (!images.isEmpty() && audio != null) {
            return new ModelReply(ModelReply.Type.MIXED, null, images, audio, filePayload);
        }
        if (!images.isEmpty() && textReply != null && !textReply.isEmpty()) {
            return ModelReply.mixed(textReply, images);
        }
        if (!images.isEmpty()) {
            return ModelReply.image(images.get(0).bytes(), images.get(0).fileName());
        }
        if (audio != null) {
            return ModelReply.voice(audio.bytes(), audio.format(), audio.durationMs(), audio.sampleRate());
        }
        return ModelReply.text(textReply != null ? textReply : "task completed");
    }

    private static ParsedFileResult extractFileMarkers(String text) {
        Matcher m = FILE_MARKER.matcher(text);
        if (!m.find()) {
            log.info("no [FILE:] marker found in chat output, first 300 chars: {}",
                    text.length() > 300 ? text.substring(0, 300) : text);
            return null;
        }
        String fileName = m.group(1).trim();
        String fileContent = m.group(2);
        String remainder = new StringBuilder(text).replace(m.start(), m.end(), "").toString().trim();
        if (fileName.isEmpty() || fileContent.isEmpty()) {
            return null;
        }
        return new ParsedFileResult(fileName, fileContent, remainder);
    }

    private record ParsedFileResult(String fileName, String fileContent, String remainderText) {}

    private static boolean isComfortStoryRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean mentionsStory = text.contains("故事");
        boolean asksToHear = text.contains("想听")
                || text.contains("讲个")
                || text.contains("睡前")
                || text.contains("给我讲");
        boolean upset = text.contains("心情不好")
                || text.contains("难过")
                || text.contains("有点难过");
        boolean asksComfort = text.contains("哄我")
                || text.contains("安慰我");
        return asksComfort || (mentionsStory && (asksToHear || upset));
    }

    private static boolean isLoginConfirmation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text,
                "已登录",
                "登录好了",
                "登录完成",
                "登录成功",
                "登陆好了",
                "登陆完成",
                "登陆成功",
                "已登陆",
                "我好了",
                "登好了",
                "登录了",
                "登陆了",
                "可以了",
                "扫好了",
                "扫码完成",
                "我已登录",
                "登进去了",
                "登录进去了"
        );
    }

    /**
     * Extracts the last user intent from conversation history, ignoring system messages
     * and login confirmations, to provide context for continuing a paused task.
     */
    private static String extractLastUserIntent(List<ChatRequest.Message> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        // Walk backwards to find the last meaningful user message
        // that is NOT a login confirmation
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatRequest.Message msg = history.get(i);
            if (!"user".equals(msg.getRole())) {
                continue;
            }
            String content = String.valueOf(msg.getContent());
            if (!isLoginConfirmation(content)) {
                // Found the original user request
                if (content.length() > 500) {
                    return content.substring(0, 500) + "...";
                }
                return content;
            }
        }
        return null;
    }

    /**
     * Extracts the last assistant (CHAT agent) output from conversation history.
     * Used to carry prepared content (e.g. article text) to the next browser task.
     */
    private static String extractLastChatOutput(List<ChatRequest.Message> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatRequest.Message msg = history.get(i);
            if ("assistant".equals(msg.getRole())) {
                String content = String.valueOf(msg.getContent());
                if (content != null && !content.isBlank()) {
                    return content;
                }
            }
        }
        return null;
    }

    private static boolean isGenerateCopyAndSpeakRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        boolean asksSpeech = containsAny(text,
                "语音",
                "朗读",
                "朗诵",
                "念给我听",
                "读给我听",
                "讲给我听",
                "说出来",
                "读出来",
                "帮我读",
                "帮我念",
                "播报",
                "念一下",
                "tts");

        boolean asksCopy = containsAny(text,
                "宣传语",
                "文案",
                "口号",
                "标题",
                "标语",
                "介绍语",
                "想一句",
                "写一句",
                "生成一句",
                "帮我写",
                "帮我想");

        return asksSpeech && asksCopy;
    }

    private static boolean isImageGenerateAndDescribeRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean wantImage = containsAny(text,
                "生成一张图片",
                "生成图片",
                "画一张",
                "画个",
                "文生图",
                "生图",
                "帮我画");
        boolean wantDescribe = containsAny(text,
                "描述",
                "解读",
                "分析",
                "说明",
                "看看");
        return wantImage && wantDescribe;
    }

    /** Returns true if the text looks like an error/fallback message from a failed task. */
    public static boolean isLikelyErrorText(String text) {
        if (text == null || text.isBlank()) return false;
        // Very short "error" texts are suspicious
        if (text.length() < 30) {
            return text.contains("失败") || text.contains("超时") || text.contains("错误")
                    || text.contains("暂不可用") || text.contains("未生成") || text.contains("无法")
                    || text.contains("不可用") || text.contains("重试") || text.contains("异常");
        }
        return false;
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String stringValue(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private record ImageMemory(List<String> imageUrls, String summary) {
        private static ImageMemory empty() {
            return new ImageMemory(List.of(), null);
        }

        private static ImageMemory of(List<String> imageUrls, String summary) {
            return new ImageMemory(imageUrls != null ? List.copyOf(imageUrls) : List.of(), summary);
        }
    }
}
