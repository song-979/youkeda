package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationRuntime;
import com.youkeda.project.wechatproject.bot.tool.chat.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.chat.RagTools;
import com.youkeda.project.wechatproject.bot.tool.chat.ScheduledTaskExecutionRequest;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapDirectionTools;
import com.youkeda.project.wechatproject.bot.tool.travel.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.workflow.DagOrchestrationService;
import com.youkeda.project.wechatproject.bot.workflow.DagRunOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes direct requests to simple mode and stateful work exclusively to the DAG runtime. */
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
    private static final Pattern FILE_MARKER = Pattern.compile(
            "\\[FILE:(.+?)]\\r?\\n(.*?)\\r?\\n\\[/FILE]", Pattern.DOTALL);
    private static final Pattern MOTOU_GIF_MARKER = Pattern.compile("\\[MOTOU_GIF:(.+?)]");
    private static final Pattern NEW_TASK_PREFIX = Pattern.compile(
            "^(?:新任务|创建新任务)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
    private static final int USER_LOCK_STRIPES = 256;
    private static final String DAG_UNAVAILABLE_MESSAGE =
            "复杂任务服务暂时不可用，请稍后重试。已完成的 DAG 节点状态不会丢失。";

    private final ReentrantLock[] userLocks = createUserLocks();
    private final ConcurrentHashMap<String, Boolean> userSimpleMode = new ConcurrentHashMap<>();
    private final ConversationMemory memory;
    private final DocumentService documentService;
    private final SimpleModeRouter simpleModeRouter;
    private final DagOrchestrationService dagOrchestrationService;
    private final TaskComplexityRouter taskComplexityRouter;

    public MessageRouter(ConversationMemory memory,
                         DocumentService documentService,
                         SimpleModeRouter simpleModeRouter,
                         DagOrchestrationService dagOrchestrationService,
                         TaskComplexityRouter taskComplexityRouter) {
        this.memory = memory;
        this.documentService = documentService;
        this.simpleModeRouter = simpleModeRouter;
        this.dagOrchestrationService = dagOrchestrationService;
        this.taskComplexityRouter = taskComplexityRouter;
    }

    public List<ModelReply> route(String userId, String text, List<String> imageBase64Urls,
                                  Consumer<String> progressCallback) throws IOException {
        return route(userId, text, imageBase64Urls, progressCallback, null);
    }

    /**
     * Routes an inbound message. Complex work is submitted asynchronously when a completion
     * callback is supplied, so later pause or modification messages are never blocked by a DAG run.
     */
    public List<ModelReply> route(String userId, String text, List<String> imageBase64Urls,
                                  Consumer<String> progressCallback,
                                  Consumer<List<ModelReply>> asyncReplyCallback) throws IOException {
        LocalFileTools.getAndClearPreparedFile();
        List<String> requestImages = imageBase64Urls != null ? imageBase64Urls : List.of();
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        installUserContext(userId);
        try {
            List<ModelReply> modeReply = handleModeCommand(userId, text);
            if (modeReply != null) {
                return modeReply;
            }
            if (userSimpleMode.getOrDefault(userId, false)) {
                return routeSimpleOrUnavailable(userId, text, requestImages);
            }

            boolean explicitNewTask = isExplicitNewTask(text);
            String effectiveText = explicitNewTask ? stripNewTaskPrefix(text) : text;
            List<ChatRequest.Message> history = memory != null
                    ? memory.getHistory(userId, effectiveText) : List.of();
            ImageMemory imageMemory = requestImages.isEmpty()
                    ? resolveImageMemory(userId) : ImageMemory.empty();
            if (!requestImages.isEmpty() && memory != null) {
                memory.rememberImageContext(userId, requestImages, text);
            }
            if (!requestImages.isEmpty() && (text == null || text.isBlank())) {
                return List.of(ModelReply.text("你发送了图片，请告诉我需要如何处理。"));
            }

            UserRequest request = new UserRequest(
                    userId, effectiveText, requestImages, history,
                    imageMemory.imageUrls(), imageMemory.summary(),
                    ContextStage.PLAN, ContextTaskState.empty());

            // One focused DAG owns unqualified messages. Only an explicit prefix starts unrelated work.
            if (!explicitNewTask && dagOrchestrationService != null
                    && (dagOrchestrationService.hasActiveDag()
                    || DagOrchestrationService.isDagControlMessage(effectiveText))) {
                DagOrchestrationService.DagSubmission submission =
                        dagOrchestrationService.handleUserMessage(
                                request, progressCallback,
                                asyncOutcomeHandler(userId, effectiveText, asyncReplyCallback));
                if (submission.status()
                        != DagOrchestrationService.DagSubmission.Status.NO_ACTIVE_DAG) {
                    log.info("[DAG] message applied dagId={} status={}",
                            submission.dagId(), submission.status());
                    return List.of(ModelReply.text(nonBlank(
                            submission.message(), "DAG 任务状态已更新。")));
                }
            }

            if (taskComplexityRouter != null && simpleModeRouter != null) {
                TaskComplexityRouter.Assessment assessment = taskComplexityRouter.assess(request);
                log.info("[ROUTE] complexity userId={} decision={} source={} reason={} latencyMs={}",
                        userId, assessment.complexity(), assessment.source(),
                        assessment.reasonCode(), assessment.latencyMs());
                if (assessment.isSimple()) {
                    log.info("[ROUTE] selected simple mode userId={} reason={}",
                            userId, assessment.reasonCode());
                    return List.of(simpleModeRouter.route(userId, text, requestImages));
                }
            }

            log.info("[DAG] route selected new task planning userId={} explicitNewTask={}",
                    userId, explicitNewTask);
            if (asyncReplyCallback == null) {
                return executeDag(userId, effectiveText, request, progressCallback);
            }
            if (dagOrchestrationService == null) {
                return dagUnavailable(userId, effectiveText, "service bean missing");
            }
            DagOrchestrationService.DagSubmission submission = dagOrchestrationService.submit(
                    request, progressCallback,
                    asyncOutcomeHandler(userId, effectiveText, asyncReplyCallback));
            if (submission.status() == DagOrchestrationService.DagSubmission.Status.UNAVAILABLE) {
                return dagUnavailable(userId, effectiveText, submission.message());
            }
            return List.of(ModelReply.text(submission.message()));
        } finally {
            clearUserContext();
            UserMessageTool.clear();
            lock.unlock();
        }
    }

    /** Scheduled work is always planned as a DAG and never enters the interactive simple route. */
    public List<ModelReply> routeScheduledTask(ScheduledTaskExecutionRequest scheduledRequest) throws IOException {
        String userId = scheduledRequest.recipientId();
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        installUserContext(userId);
        try {
            LocalFileTools.getAndClearPreparedFile();
            String prompt = scheduledTaskPrompt(scheduledRequest);
            List<ChatRequest.Message> history = memory != null
                    ? memory.getHistory(userId, prompt) : List.of();
            UserRequest request = new UserRequest(
                    userId, prompt, List.of(), history, List.of(), null,
                    ContextStage.SCHEDULED, ContextTaskState.empty());
            if (dagOrchestrationService == null) {
                throw new IOException("DAG orchestration service is unavailable");
            }
            log.info("[DAG] scheduled task planning userId={} taskId={}",
                    userId, scheduledRequest.taskId());
            var outcome = dagOrchestrationService.executeNew(request, null);
            if (outcome.isEmpty()) {
                throw new IOException("DAG planning unavailable; retry the scheduled task");
            }
            if (outcome.get().status() == DagRunOutcome.Status.WAITING_USER) {
                throw new IOException(nonBlank(
                        outcome.get().messageToUser(), "scheduled task requires user information"));
            }
            return finishCompletedOutcome(
                    userId, scheduledMemoryText(scheduledRequest), outcome.get());
        } finally {
            clearUserContext();
            UserMessageTool.clear();
            lock.unlock();
        }
    }

    private List<ModelReply> executeDag(String userId, String userText, UserRequest request,
                                        Consumer<String> progressCallback) {
        if (dagOrchestrationService == null) {
            return dagUnavailable(userId, userText, "service bean missing");
        }
        var outcome = dagOrchestrationService.execute(request, progressCallback);
        if (outcome.isEmpty()) {
            return dagUnavailable(userId, userText, "planner or runtime unavailable");
        }
        log.info("[DAG] route outcome userId={} status={}", userId, outcome.get().status());
        if (outcome.get().status() == DagRunOutcome.Status.WAITING_USER) {
            String message = nonBlank(outcome.get().messageToUser(), "请补充信息后继续。");
            appendMemory(userId, userText, message);
            if (!outcome.get().pausedImages().isEmpty()) {
                return List.of(ModelReply.mixed(message, outcome.get().pausedImages()));
            }
            return List.of(ModelReply.text(message));
        }
        return finishCompletedOutcome(userId, userText, outcome.get());
    }

    private Consumer<DagRunOutcome> asyncOutcomeHandler(
            String userId, String userText, Consumer<List<ModelReply>> replyCallback) {
        if (replyCallback == null) return null;
        return outcome -> {
            List<ModelReply> replies;
            if (outcome.status() == DagRunOutcome.Status.WAITING_USER) {
                String message = nonBlank(outcome.messageToUser(), "请补充信息后继续。");
                appendMemory(userId, userText, message);
                replies = outcome.pausedImages().isEmpty()
                        ? List.of(ModelReply.text(message))
                        : List.of(ModelReply.mixed(message, outcome.pausedImages()));
            } else if (outcome.status() == DagRunOutcome.Status.PAUSED) {
                replies = List.of(ModelReply.text(nonBlank(
                        outcome.messageToUser(), "DAG 任务已暂停。")));
            } else {
                replies = finishCompletedOutcome(userId, userText, outcome);
            }
            replyCallback.accept(replies);
        };
    }

    private List<ModelReply> finishCompletedOutcome(String userId, String userText,
                                                    DagRunOutcome outcome) {
        List<ModelReply> replies = buildFinalReplies(outcome);
        if (replies.isEmpty()) {
            replies = List.of(ModelReply.text(nonBlank(
                    outcome.finalReply(), "任务已结束，但没有可用结果。")));
        }
        persistDagMemory(userId, userText, outcome, replies);
        return replies;
    }

    private List<ModelReply> dagUnavailable(String userId, String userText, String reason) {
        log.warn("[DAG] request unavailable userId={} reason={}", userId, reason);
        appendMemory(userId, userText, DAG_UNAVAILABLE_MESSAGE);
        return List.of(ModelReply.text(DAG_UNAVAILABLE_MESSAGE));
    }

    private List<ModelReply> handleModeCommand(String userId, String text) {
        if (text == null) {
            return null;
        }
        if (text.trim().equals("/easy")) {
            userSimpleMode.put(userId, true);
            log.info("user {} switched to simple mode", userId);
            return List.of(ModelReply.text("已切换到简单模式。"));
        }
        if (text.trim().equals("/normal")) {
            userSimpleMode.remove(userId);
            log.info("user {} switched to automatic complexity routing", userId);
            return List.of(ModelReply.text("已切换到自动模式。"));
        }
        return null;
    }

    private List<ModelReply> routeSimpleOrUnavailable(
            String userId, String text, List<String> images) throws IOException {
        if (simpleModeRouter == null) {
            return dagUnavailable(userId, text, "simple mode router missing");
        }
        return List.of(simpleModeRouter.route(userId, text, images));
    }

    private static boolean isExplicitNewTask(String text) {
        return text != null && NEW_TASK_PREFIX.matcher(text.trim()).find();
    }

    private static String stripNewTaskPrefix(String text) {
        if (text == null) return "";
        return NEW_TASK_PREFIX.matcher(text.trim()).replaceFirst("").trim();
    }

    private List<ModelReply> buildFinalReplies(DagRunOutcome outcome) {
        List<DagRunOutcome.NodeOutput> outputs = outcome.nodeOutputs();
        List<ModelReply> replies = new ArrayList<>();
        if (!outputs.isEmpty()) {
            long successCount = outputs.stream()
                    .filter(output -> output.result().status() == AgentResult.Status.SUCCESS)
                    .count();
            String header = "任务结果：共 " + outputs.size() + " 步，成功 " + successCount;
            if (successCount < outputs.size()) {
                header += "，未完成 " + (outputs.size() - successCount);
            }
            replies.add(ModelReply.text(header));
        }

        for (DagRunOutcome.NodeOutput node : outputs) {
            if (node.result().status() != AgentResult.Status.SUCCESS) {
                continue;
            }
            Object output = node.result().output();
            if (output instanceof GeneratedImage image && "IMAGE_GEN".equals(node.agentType())) {
                replies.add(ModelReply.image(image.bytes(), image.fileName()));
                continue;
            }
            if (output instanceof byte[] bytes && "IMAGE_GEN".equals(node.agentType())) {
                replies.add(ModelReply.image(bytes, "generated.png"));
                continue;
            }
            if (output instanceof SpeechAgent.TtsOutput speech) {
                replies.add(ModelReply.voice(speech.audioBytes(), speech.format(),
                        speech.durationMs(), speech.sampleRate()));
                continue;
            }
            String text = textForNode(node);
            ParsedFileResult parsed = text != null ? extractFileMarkers(text) : null;
            if (parsed != null && documentService != null) {
                if (!parsed.remainderText().isBlank()) {
                    replies.add(ModelReply.text(
                            agentDisplayName(node.agentType()) + ":\n" + parsed.remainderText()));
                }
                DocumentService.GeneratedFile file = documentService.generate(
                        parsed.fileContent(), parsed.fileName());
                replies.add(ModelReply.file(file.bytes(), file.fileName()));
            } else if (text != null && !text.isBlank()) {
                replies.add(ModelReply.text(agentDisplayName(node.agentType()) + ":\n" + text));
            }
        }

        appendPreparedLocalFile(replies);
        appendMotouGif(outputs, replies);
        AmapAroundSearchTools.drainMapImages()
                .forEach(bytes -> replies.add(ModelReply.image(bytes, "amap_around.png")));
        AmapDirectionTools.drainMapImages()
                .forEach(bytes -> replies.add(ModelReply.image(bytes, "amap_route.png")));

        List<DagRunOutcome.NodeOutput> failed = outputs.stream()
                .filter(output -> output.result().status() != AgentResult.Status.SUCCESS)
                .toList();
        if (!failed.isEmpty()) {
            StringBuilder message = new StringBuilder("未完成的任务：");
            for (DagRunOutcome.NodeOutput node : failed) {
                message.append("\n- ").append(agentDisplayName(node.agentType()));
                if (node.result().errorMessage() != null && !node.result().errorMessage().isBlank()) {
                    message.append(": ").append(node.result().errorMessage());
                }
            }
            replies.add(ModelReply.text(message.toString()));
        }
        if (outcome.finalReply() != null && !outcome.finalReply().isBlank()) {
            replies.add(ModelReply.text(outcome.finalReply()));
        }
        return replies;
    }

    private void persistDagMemory(String userId, String userText, DagRunOutcome outcome,
                                  List<ModelReply> replies) {
        if (memory == null) {
            return;
        }
        String assistantText = lastText(replies);
        if (assistantText == null) {
            assistantText = nonBlank(outcome.finalReply(), mediaMemoryMarker(replies));
        }
        memory.append(userId, userText, assistantText != null ? assistantText : "");

        List<String> imageUrls = outcome.nodeOutputs().stream()
                .filter(node -> node.result().status() == AgentResult.Status.SUCCESS)
                .map(DagRunOutcome.NodeOutput::result)
                .map(AgentResult::output)
                .filter(GeneratedImage.class::isInstance)
                .map(GeneratedImage.class::cast)
                .map(GeneratedImage::dataUrl)
                .toList();
        if (!imageUrls.isEmpty()) {
            memory.rememberImageContext(userId, imageUrls,
                    nonBlank(lastSuccessfulChatText(outcome), outcome.finalReply()));
        }
    }

    private void appendMemory(String userId, String userText, String assistantText) {
        if (memory == null) {
            return;
        }
        try {
            memory.append(userId, userText, assistantText != null ? assistantText : "");
        } catch (Exception e) {
            log.warn("failed to append conversation memory userId={} error={}", userId, e.getMessage());
        }
    }

    private ImageMemory resolveImageMemory(String userId) {
        if (memory == null) {
            return ImageMemory.empty();
        }
        return ImageMemory.of(memory.getLatestImageDataUrls(userId), memory.getLatestImageSummary(userId));
    }

    private static String lastSuccessfulChatText(DagRunOutcome outcome) {
        List<DagRunOutcome.NodeOutput> outputs = outcome.nodeOutputs();
        for (int i = outputs.size() - 1; i >= 0; i--) {
            DagRunOutcome.NodeOutput node = outputs.get(i);
            if ("CHAT".equals(node.agentType())
                    && node.result().status() == AgentResult.Status.SUCCESS
                    && node.result().rawOutput() != null
                    && !node.result().rawOutput().isBlank()) {
                return node.result().rawOutput();
            }
        }
        return null;
    }

    private static String textForNode(DagRunOutcome.NodeOutput node) {
        if ("IMAGE_GEN".equals(node.agentType())) {
            return "图片已生成。";
        }
        if ("SPEECH_GEN".equals(node.agentType())) {
            return "语音已生成。";
        }
        AgentResult result = node.result();
        if (result.rawOutput() != null && !result.rawOutput().isBlank()) {
            return result.rawOutput();
        }
        return result.output() instanceof String text && !text.isBlank() ? text : null;
    }

    private static void appendPreparedLocalFile(List<ModelReply> replies) {
        LocalFileTools.PreparedFile file = LocalFileTools.getAndClearPreparedFile();
        if (file != null) {
            replies.add(ModelReply.file(file.bytes(), file.fileName()));
        }
    }

    private static void appendMotouGif(List<DagRunOutcome.NodeOutput> outputs,
                                       List<ModelReply> replies) {
        for (DagRunOutcome.NodeOutput node : outputs) {
            String rawOutput = node.result().rawOutput();
            if (node.result().status() != AgentResult.Status.SUCCESS || rawOutput == null) {
                continue;
            }
            Matcher matcher = MOTOU_GIF_MARKER.matcher(rawOutput);
            if (!matcher.find()) {
                continue;
            }
            String gifPath = matcher.group(1).trim();
            try {
                Path safePath = validateGeneratedFilePath(gifPath);
                replies.add(ModelReply.file(Files.readAllBytes(safePath), "motou.gif"));
            } catch (IOException e) {
                log.error("failed to read MOTOU_GIF from path={}", gifPath, e);
            }
            return;
        }
    }

    private static String lastText(List<ModelReply> replies) {
        for (int i = replies.size() - 1; i >= 0; i--) {
            String text = replies.get(i).getTextContent();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String mediaMemoryMarker(List<ModelReply> replies) {
        if (replies.stream().anyMatch(reply -> reply.getType() == ModelReply.Type.IMAGE)) {
            return "[image-generated]";
        }
        if (replies.stream().anyMatch(reply -> reply.getType() == ModelReply.Type.VOICE)) {
            return "[voice-generated]";
        }
        return null;
    }

    private static String scheduledMemoryText(ScheduledTaskExecutionRequest request) {
        if (request.originalRequest() != null && !request.originalRequest().isBlank()) {
            return request.originalRequest();
        }
        if (request.instruction() != null && !request.instruction().isBlank()) {
            return "[scheduled-task] " + request.instruction();
        }
        return "[scheduled-task] " + request.title();
    }

    private static String scheduledTaskPrompt(ScheduledTaskExecutionRequest request) {
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                ZonedDateTime.now(ZoneId.systemDefault()));
        StringBuilder prompt = new StringBuilder();
        prompt.append("[scheduled-task-trigger]\n")
                .append("Current application time: ").append(now).append('\n')
                .append("The configured trigger time has arrived. Execute the saved user task now.\n\n")
                .append("Task id: ").append(request.taskId()).append('\n')
                .append("Title: ").append(request.title()).append('\n')
                .append("Scheduled for: ").append(request.scheduledFor()).append('\n');
        if (request.originalRequest() != null && !request.originalRequest().isBlank()) {
            prompt.append("Original user request: ").append(request.originalRequest()).append('\n');
        }
        if (!request.expectedToolCategories().isEmpty()) {
            prompt.append("Expected tool categories for audit only, not a restriction: ")
                    .append(String.join(", ", request.expectedToolCategories())).append('\n');
        }
        return prompt.append("\nSaved instruction to execute now:\n")
                .append(request.instruction())
                .append("\n\nRules:\n")
                .append("- Execute the saved instruction now; do not report stale creation-time data.\n")
                .append("- Do not create, update, or delete automation unless explicitly requested.\n")
                .append("- If required information is missing, return a concise failure reason.\n")
                .toString();
    }

    private static Path validateGeneratedFilePath(String rawPath) throws IOException {
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("generated file does not exist: " + candidate);
        }
        Path real = candidate.toRealPath();
        List<Path> allowedRoots = List.of(
                Path.of("").toAbsolutePath().normalize().toRealPath(),
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toRealPath());
        if (allowedRoots.stream().noneMatch(real::startsWith)) {
            throw new IOException("generated file is outside allowed roots: " + real);
        }
        return real;
    }

    private static ParsedFileResult extractFileMarkers(String text) {
        Matcher matcher = FILE_MARKER.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String fileName = matcher.group(1).trim();
        String fileContent = matcher.group(2);
        if (fileName.isEmpty() || fileContent.isEmpty()) {
            return null;
        }
        String remainder = new StringBuilder(text)
                .replace(matcher.start(), matcher.end(), "").toString().trim();
        return new ParsedFileResult(fileName, fileContent, remainder);
    }

    private static String agentDisplayName(String agentType) {
        return switch (agentType != null ? agentType : "") {
            case "CHAT" -> "对话/轻工具";
            case "TRAVEL" -> "出行";
            case "BROWSER" -> "浏览器";
            case "IMAGE_GEN" -> "图片生成";
            case "SPEECH_GEN" -> "语音生成";
            default -> agentType != null ? agentType : "未知Agent";
        };
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static ReentrantLock[] createUserLocks() {
        ReentrantLock[] locks = new ReentrantLock[USER_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock(true);
        }
        return locks;
    }

    private ReentrantLock lockFor(String userId) {
        return userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
    }

    private static void installUserContext(String userId) {
        AutomationRuntime.setCurrentUser(userId);
        DiDiTaxiTools.setCurrentUser(userId);
        RagTools.setCurrentUser(userId);
    }

    private static void clearUserContext() {
        AutomationRuntime.clearCurrentUser();
        DiDiTaxiTools.clearCurrentUser();
        RagTools.clearCurrentUser();
    }

    private record ParsedFileResult(String fileName, String fileContent, String remainderText) {
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
