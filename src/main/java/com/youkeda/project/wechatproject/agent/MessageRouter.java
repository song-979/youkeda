package com.youkeda.project.wechatproject.agent;

import com.youkeda.project.wechatproject.agent.orchestration.AgentRegistry;
import com.youkeda.project.wechatproject.agent.orchestration.AgentResult;
import com.youkeda.project.wechatproject.agent.orchestration.AgentTask;
import com.youkeda.project.wechatproject.agent.orchestration.AgentUnit;
import com.youkeda.project.wechatproject.agent.orchestration.OrchestrationResult;
import com.youkeda.project.wechatproject.agent.orchestration.OrchestratorAgent;
import com.youkeda.project.wechatproject.agent.orchestration.TaskScratchpad;
import com.youkeda.project.wechatproject.agent.orchestration.UserRequest;
import com.youkeda.project.wechatproject.agent.orchestration.SpeechAgent;
import com.youkeda.project.wechatproject.agent.speech.VoiceCatalog;
import com.youkeda.project.wechatproject.agent.speech.VoiceProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
    private static final int MAX_LOOPS = 5;
    private static final Pattern FILE_MARKER = Pattern.compile(
            "\\[FILE:(.+?)]\\r?\\n(.*?)\\r?\\n\\[/FILE]", Pattern.DOTALL);

    private final OrchestratorAgent orchestrator;
    private final AgentRegistry registry;
    private final ConversationMemory memory;
    private final VoiceCatalog voiceCatalog;
    private final FileGenerator fileGenerator;

    public MessageRouter(OrchestratorAgent orchestrator,
                         AgentRegistry registry,
                         ConversationMemory memory,
                         VoiceCatalog voiceCatalog,
                         FileGenerator fileGenerator) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.memory = memory;
        this.voiceCatalog = voiceCatalog;
        this.fileGenerator = fileGenerator;
    }

    public ModelReply route(String userId, String text, List<String> imageBase64Urls) throws IOException {
        List<ChatRequest.Message> history = memory != null ? memory.getHistory(userId) : List.of();
        ImageMemory imageMemory = resolveImageMemory(userId, text);

        UserRequest request = new UserRequest(
                userId,
                text,
                imageBase64Urls,
                history,
                imageMemory.imageUrls(),
                imageMemory.summary());

        OrchestrationResult result = specialCasePlan(request);
        if (result == null) {
            result = orchestrator.plan(request);
        }

        log.info("orchestrator plan: status={}, reasoning={}", result.status(), result.reasoning());

        if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
            String question = result.clarificationQuestion();
            if (memory != null) {
                memory.append(userId, text, question);
            }
            return ModelReply.text(question);
        }

        if (result.status() == OrchestrationResult.Status.COMPLETED) {
            ModelReply finalReply = result.finalReply() != null ? result.finalReply() : ModelReply.text("completed");
            persistMemory(userId, text, result, finalReply);
            return finalReply;
        }

        int loops = 0;
        while (result.status() == OrchestrationResult.Status.EXECUTE && loops < MAX_LOOPS) {
            loops++;

            for (AgentTask task : result.tasks()) {
                AgentTask executableTask = hydrateTask(request, result.scratchpad(), task);
                try {
                    AgentUnit worker = registry.get(executableTask.agentType());
                    AgentResult agentResult = worker.execute(executableTask);
                    result.scratchpad().record(executableTask, agentResult);
                    log.info("task executed: agent={}, status={}, taskId={}",
                            executableTask.agentType(), agentResult.status(), executableTask.taskId());
                } catch (Exception e) {
                    log.error("task execution failed: agent={}, taskId={}, error={}",
                            executableTask.agentType(), executableTask.taskId(), e.getMessage());
                    result.scratchpad().record(executableTask,
                            AgentResult.failed(executableTask.taskId(), e.getMessage()));
                }
            }

            result = orchestrator.reflect(result.scratchpad());
            log.info("orchestrator reflect: status={}, reasoning={}", result.status(), result.reasoning());

            if (result.status() == OrchestrationResult.Status.NEEDS_CLARIFICATION) {
                if (memory != null) {
                    memory.append(userId, text, result.clarificationQuestion());
                }
                return ModelReply.text(result.clarificationQuestion());
            }
        }

        if (loops >= MAX_LOOPS) {
            log.warn("orchestrator hit max loops ({}), forcing completion", MAX_LOOPS);
        }

        ModelReply finalReply = buildFinalReply(result);
        persistMemory(userId, text, result, finalReply);
        return finalReply;
    }

    private OrchestrationResult specialCasePlan(UserRequest request) {
        String text = request.text() == null ? "" : request.text().trim();

        if (isComfortStoryRequest(text)) {
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

        if (isGenerateCopyAndSpeakRequest(text)) {
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

        if (isImageGenerateAndDescribeRequest(text)) {
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
                taskHistory.add(new ChatRequest.Message("assistant", "[remembered-image-summary] " + request.rememberedImageSummary()));
            }
            if (!taskHistory.isEmpty()) {
                params.put("history", taskHistory);
            }
        }

        if ("SPEECH_GEN".equals(task.agentType())) {
            params.put("text", resolveSpeechText(task, scratchpad));
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
        String chatText = result.scratchpad().lastSuccessfulChatText();
        if (chatText != null && !chatText.isBlank()) {
            return chatText;
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

    private ImageMemory resolveImageMemory(String userId, String text) {
        if (memory == null || !referencesImage(text)) {
            return ImageMemory.empty();
        }
        List<String> imageUrls = memory.getLatestImageDataUrls(userId);
        return ImageMemory.of(imageUrls, memory.getLatestImageSummary(userId));
    }

    private ModelReply buildFinalReply(OrchestrationResult result) {
        TaskScratchpad scratchpad = result.scratchpad();
        String textReply = scratchpad.lastSuccessfulChatText();
        if (textReply == null || textReply.isBlank()) {
            textReply = result.finalReply() != null ? result.finalReply().getTextContent() : null;
        }

        // 检测 ChatAgent 输出中的 [FILE:xxx]...[/FILE] 标记
        ModelReply.FilePayload filePayload = null;
        if (textReply != null) {
            ParsedFileResult parsed = extractFileMarkers(textReply);
            if (parsed != null) {
                textReply = parsed.remainderText();
                byte[] fileBytes = fileGenerator.generate(parsed.fileContent(), parsed.fileName());
                filePayload = new ModelReply.FilePayload(fileBytes, parsed.fileName());
            }
        }

        List<ModelReply.ImagePayload> images = new ArrayList<>();
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

        // 文件 + 图片 + 文本混合
        if (filePayload != null && !images.isEmpty() && textReply != null && !textReply.isBlank()) {
            return ModelReply.mixedWithFile(textReply, images, filePayload);
        }
        // 文件 + 图片（无文本）
        if (filePayload != null && !images.isEmpty()) {
            return ModelReply.mixedWithFile(null, images, filePayload);
        }
        // 纯文件
        if (filePayload != null) {
            return ModelReply.file(filePayload.bytes(), filePayload.fileName());
        }

        // 图片 + 语音 + 文本
        if (!images.isEmpty() && audio != null && textReply != null && !textReply.isBlank()) {
            return new ModelReply(ModelReply.Type.MIXED, textReply, images, audio, filePayload);
        }
        // 图片 + 语音（无文本）
        if (!images.isEmpty() && audio != null) {
            return new ModelReply(ModelReply.Type.MIXED, null, images, audio, filePayload);
        }
        // 图片 + 文本
        if (!images.isEmpty() && textReply != null && !textReply.isEmpty()) {
            return ModelReply.mixed(textReply, images);
        }
        // 纯图片
        if (!images.isEmpty()) {
            return ModelReply.image(images.get(0).bytes(), images.get(0).fileName());
        }
        // 纯语音
        if (audio != null) {
            return ModelReply.voice(audio.bytes(), audio.format(), audio.durationMs(), audio.sampleRate());
        }
        return ModelReply.text(textReply != null ? textReply : "task completed");
    }

    /**
     * 从文本中提取 [FILE:文件名]...[ /FILE] 标记，返回剥离后的文本和文件信息。
     */
    private static ParsedFileResult extractFileMarkers(String text) {
        Matcher m = FILE_MARKER.matcher(text);
        if (!m.find()) {
            log.info("no [FILE:] marker found in chat output, first 300 chars: {}",
                    text.length() > 300 ? text.substring(0, 300) : text);
            return null;
        }
        String fileName = m.group(1).trim();
        String fileContent = m.group(2);
        String remainder = new StringBuilder(text)
                .replace(m.start(), m.end(), "")
                .toString().trim();
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
        boolean mentionsStory = text.contains("\u6545\u4e8b");
        boolean asksToHear = text.contains("\u60f3\u542c")
                || text.contains("\u8bb2\u4e2a")
                || text.contains("\u7761\u524d")
                || text.contains("\u7ed9\u6211\u8bb2");
        boolean upset = text.contains("\u5fc3\u60c5\u4e0d\u597d")
                || text.contains("\u96be\u8fc7")
                || text.contains("\u6709\u70b9\u96be\u8fc7");
        boolean asksComfort = text.contains("\u54c4\u6211")
                || text.contains("\u5b89\u6170\u6211");
        return asksComfort || (mentionsStory && (asksToHear || upset));
    }

    private static boolean isGenerateCopyAndSpeakRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        boolean asksSpeech = containsAny(text,
                "\u8bed\u97f3",
                "\u6717\u8bfb",
                "\u5ff5\u7ed9\u6211\u542c",
                "\u8bfb\u7ed9\u6211\u542c",
                "\u8bb2\u7ed9\u6211\u542c",
                "\u8bf4\u51fa\u6765",
                "\u8bfb\u51fa\u6765",
                "\u5e2e\u6211\u8bfb",
                "tts");

        boolean asksCopy = containsAny(text,
                "\u5ba3\u4f20\u8bed",
                "\u6587\u6848",
                "\u53e3\u53f7",
                "\u6807\u9898",
                "\u6807\u8bed",
                "\u4ecb\u7ecd\u8bed",
                "\u60f3\u4e00\u53e5",
                "\u5199\u4e00\u53e5",
                "\u751f\u6210\u4e00\u53e5",
                "\u5e2e\u6211\u5199",
                "\u5e2e\u6211\u60f3");

        return asksSpeech && asksCopy;
    }

    private static boolean isImageGenerateAndDescribeRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean wantImage = containsAny(text,
                "\u751f\u6210\u4e00\u5f20\u56fe\u7247",
                "\u751f\u6210\u56fe\u7247",
                "\u753b\u4e00\u5f20",
                "\u753b\u4e2a",
                "\u6587\u751f\u56fe",
                "\u751f\u56fe",
                "\u5e2e\u6211\u753b");
        boolean wantDescribe = containsAny(text,
                "\u63cf\u8ff0",
                "\u89e3\u8bfb",
                "\u5206\u6790",
                "\u8bf4\u660e",
                "\u770b\u770b");
        return wantImage && wantDescribe;
    }

    private static boolean referencesImage(String text) {
        if (text == null || text.isBlank() || !text.contains("\u56fe")) {
            return false;
        }
        return containsAny(text,
                "\u8fd9\u5f20",
                "\u8fd9\u5e45",
                "\u521a\u624d",
                "\u4e0a\u4e00",
                "\u7ee7\u7eed",
                "\u518d",
                "\u4fee\u6539",
                "\u57fa\u4e8e",
                "\u91cc");
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
