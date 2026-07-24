package com.youkeda.project.wechatproject.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatResponse;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TextToSpeechClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TtsResult;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceCatalog;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceProfile;
import com.youkeda.project.wechatproject.bot.tool.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.AmapDirectionTools;
import com.youkeda.project.wechatproject.bot.tool.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.tool.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.LocationAuthorizationTools;
import com.youkeda.project.wechatproject.bot.tool.MotouTool;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-agent orchestration namespace.
 */
public final class OrchestrationService {

    private OrchestrationService() {
    }

    public static class ModelReply {

        public enum Type {
            TEXT,
            IMAGE,
            MIXED,
            VOICE,
            FILE
        }

        public record ImagePayload(byte[] bytes, String fileName) {
            public ImagePayload {
                Objects.requireNonNull(bytes, "image bytes must not be null");
                Objects.requireNonNull(fileName, "image fileName must not be null");
            }
        }

        public record AudioPayload(byte[] bytes, String format, int durationMs, int sampleRate) {
            public AudioPayload {
                Objects.requireNonNull(bytes, "audio bytes must not be null");
                Objects.requireNonNull(format, "audio format must not be null");
            }
        }

        public record FilePayload(byte[] bytes, String fileName) {
            public FilePayload {
                Objects.requireNonNull(bytes, "file bytes must not be null");
                Objects.requireNonNull(fileName, "file fileName must not be null");
            }
        }

        private final Type type;
        private final String textContent;
        private final List<ImagePayload> images;
        private final AudioPayload audioPayload;
        private final FilePayload filePayload;

        public ModelReply(Type type, String textContent, List<ImagePayload> images,
                          AudioPayload audioPayload, FilePayload filePayload) {
            this.type = type;
            this.textContent = textContent;
            this.images = images != null ? Collections.unmodifiableList(images) : Collections.emptyList();
            this.audioPayload = audioPayload;
            this.filePayload = filePayload;
        }

        public Type getType() { return type; }
        public String getTextContent() { return textContent; }
        public List<ImagePayload> getImages() { return images; }
        public AudioPayload getAudioPayload() { return audioPayload; }
        public FilePayload getFilePayload() { return filePayload; }

        public static ModelReply text(String text) {
            return new ModelReply(Type.TEXT, text, Collections.emptyList(), null, null);
        }

        public static ModelReply image(byte[] bytes, String fileName) {
            ImagePayload payload = new ImagePayload(bytes, fileName);
            return new ModelReply(Type.IMAGE, null, List.of(payload), null, null);
        }

        public static ModelReply mixed(String text, List<ImagePayload> images) {
            return new ModelReply(Type.MIXED, text, images, null, null);
        }

        public static ModelReply voice(byte[] audioBytes, String format, int durationMs, int sampleRate) {
            AudioPayload payload = new AudioPayload(audioBytes, format, durationMs, sampleRate);
            return new ModelReply(Type.VOICE, null, Collections.emptyList(), payload, null);
        }

        public static ModelReply file(byte[] fileBytes, String fileName) {
            FilePayload payload = new FilePayload(fileBytes, fileName);
            return new ModelReply(Type.FILE, null, Collections.emptyList(), null, payload);
        }

        public static ModelReply mixedWithFile(String text, List<ImagePayload> images, FilePayload filePayload) {
            return new ModelReply(Type.MIXED, text, images, null, filePayload);
        }
    }

    public interface ConversationMemory {
        List<ChatRequest.Message> getHistory(String userId);

        void append(String userId, String userMessage, String assistantReply);

        void appendUserMessage(String userId, String userMessage);

        void clear(String userId);

        default void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
        }

        default List<String> getLatestImageDataUrls(String userId) {
            return List.of();
        }

        default String getLatestImageSummary(String userId) {
            return null;
        }
    }

    public static class InMemoryConversationMemory implements ConversationMemory {

        private static final Logger log = LoggerFactory.getLogger(InMemoryConversationMemory.class);

        private final int maxMessages;
        private final long ttlMillis;
        private final Map<String, UserSlot> store = new ConcurrentHashMap<>(64);

        public InMemoryConversationMemory(int maxHistoryRounds, int memoryTtlMinutes) {
            this.maxMessages = maxHistoryRounds * 2;
            this.ttlMillis = memoryTtlMinutes * 60_000L;
        }

        @Override
        public List<ChatRequest.Message> getHistory(String userId) {
            UserSlot slot = store.get(userId);
            if (slot == null) {
                return List.of();
            }
            if (isExpired(slot)) {
                store.remove(userId);
                log.debug("history expired for user={}", userId);
                return List.of();
            }
            synchronized (slot) {
                slot.lastAccess = System.currentTimeMillis();
                return new ArrayList<>(slot.messages);
            }
        }

        @Override
        public void append(String userId, String userMessage, String assistantReply) {
            long now = System.currentTimeMillis();
            UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
            synchronized (slot) {
                slot.lastAccess = now;

                slot.messages.addLast(new ChatRequest.Message("user", userMessage));
                slot.messages.addLast(new ChatRequest.Message("assistant", assistantReply));

                while (slot.messages.size() > maxMessages) {
                    slot.messages.removeFirst();
                }
            }
        }

        @Override
        public void appendUserMessage(String userId, String userMessage) {
            long now = System.currentTimeMillis();
            UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
            synchronized (slot) {
                slot.lastAccess = now;

                slot.messages.addLast(new ChatRequest.Message("user", userMessage));

                while (slot.messages.size() > maxMessages) {
                    slot.messages.removeFirst();
                }
            }
        }

        @Override
        public void clear(String userId) {
            store.remove(userId);
            log.debug("history cleared for user={}", userId);
        }

        @Override
        public void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
            if (userId == null || userId.isBlank() || imageBase64Urls == null || imageBase64Urls.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
            slot.lastAccess = now;
            slot.latestImageDataUrls = List.copyOf(imageBase64Urls);
            slot.latestImageSummary = summary;
        }

        @Override
        public List<String> getLatestImageDataUrls(String userId) {
            UserSlot slot = store.get(userId);
            if (slot == null || isExpired(slot) || slot.latestImageDataUrls == null || slot.latestImageDataUrls.isEmpty()) {
                return List.of();
            }
            slot.lastAccess = System.currentTimeMillis();
            return List.copyOf(slot.latestImageDataUrls);
        }

        @Override
        public String getLatestImageSummary(String userId) {
            UserSlot slot = store.get(userId);
            if (slot == null || isExpired(slot)) {
                return null;
            }
            slot.lastAccess = System.currentTimeMillis();
            return slot.latestImageSummary;
        }

        private boolean isExpired(UserSlot slot) {
            return System.currentTimeMillis() - slot.lastAccess > ttlMillis;
        }

        private static class UserSlot {
            final Deque<ChatRequest.Message> messages = new ArrayDeque<>();
            volatile List<String> latestImageDataUrls;
            volatile String latestImageSummary;
            volatile long lastAccess;

            UserSlot(long lastAccess) {
                this.lastAccess = lastAccess;
            }
        }
    }

    public record AgentCapability(String name, String description, List<String> strengths, String outputType) {
        public AgentCapability {
            strengths = strengths != null ? List.copyOf(strengths) : List.of();
        }
    }

    public static class AgentRegistry {

        private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

        private final Map<String, AgentUnit> agents = new ConcurrentHashMap<>();
        private final VoiceCatalog voiceCatalog;

        public AgentRegistry(List<AgentUnit> agentUnits, VoiceCatalog voiceCatalog) {
            this.voiceCatalog = voiceCatalog;
            for (AgentUnit unit : agentUnits) {
                agents.put(unit.getName(), unit);
                log.info("registered agent unit: {} ({})", unit.getName(), unit.getCapability().description());
            }
        }

        public AgentUnit get(String name) {
            AgentUnit unit = agents.get(name);
            if (unit == null) {
                throw new IllegalArgumentException("Unknown agent type: " + name + ". Available: " + agents.keySet());
            }
            return unit;
        }

        public boolean contains(String name) {
            return agents.containsKey(name);
        }

        public String generateCapabilitiesPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("Available agent units:\n");
            for (AgentUnit unit : agents.values()) {
                AgentCapability cap = unit.getCapability();
                sb.append("- ").append(unit.getName()).append(": ").append(cap.description()).append("\n");
                sb.append("  strengths: ").append(String.join(", ", cap.strengths())).append("\n");
                sb.append("  output: ").append(cap.outputType()).append("\n");
            }
            if (agents.containsKey("SPEECH_GEN") && voiceCatalog != null) {
                sb.append(voiceCatalog.generateVoicePrompt());
            }
            return sb.toString();
        }

        public Map<String, AgentUnit> all() {
            return Map.copyOf(agents);
        }
    }

    public static class AgentResult {

        public enum Status { SUCCESS, FAILED }

        private final String taskId;
        private final Status status;
        private final Object output;
        private final String rawOutput;
        private final String errorMessage;

        public AgentResult(String taskId, Status status, Object output, String rawOutput, String errorMessage) {
            this.taskId = taskId;
            this.status = status;
            this.output = output;
            this.rawOutput = rawOutput;
            this.errorMessage = errorMessage;
        }

        public String taskId() { return taskId; }
        public Status status() { return status; }
        public Object output() { return output; }
        public String rawOutput() { return rawOutput; }
        public String errorMessage() { return errorMessage; }

        public static AgentResult success(String taskId, Object output, String rawOutput) {
            return new AgentResult(taskId, Status.SUCCESS, output, rawOutput, null);
        }

        public static AgentResult failed(String taskId, String errorMessage) {
            return new AgentResult(taskId, Status.FAILED, null, null, errorMessage);
        }
    }

    public static class AgentTask {

        private final String taskId;
        private final String agentType;
        private final String instruction;
        private final Map<String, Object> parameters;

        public AgentTask(String agentType, String instruction, Map<String, Object> parameters) {
            this(UUID.randomUUID().toString().substring(0, 8), agentType, instruction, parameters);
        }

        private AgentTask(String taskId, String agentType, String instruction, Map<String, Object> parameters) {
            this.taskId = taskId;
            this.agentType = agentType;
            this.instruction = instruction;
            this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        }

        public AgentTask withParameters(Map<String, Object> newParameters) {
            return new AgentTask(taskId, agentType, instruction, newParameters);
        }

        public String taskId() { return taskId; }
        public String agentType() { return agentType; }
        public String instruction() { return instruction; }
        public Map<String, Object> parameters() { return parameters; }
    }

    public interface AgentUnit {
        String getName();

        AgentCapability getCapability();

        AgentResult execute(AgentTask task) throws IOException;
    }

    public static class ChatAgent implements AgentUnit {

        private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);
        private final AiModelClient chatClient;
        private final AgentProperties agentProperties;
        private final ChatClient toolChatClient;
        private final String toolCategories;

        public ChatAgent(AiModelClient chatClient) {
            this(chatClient, null, null, "");
        }

        public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                         ToolChatClientFactory toolChatClientFactory) {
            this(chatClient, agentProperties, toolChatClientFactory, "");
        }

        public ChatAgent(AiModelClient chatClient, AgentProperties agentProperties,
                         ToolChatClientFactory toolChatClientFactory, String toolCategories) {
            this.chatClient = chatClient;
            this.agentProperties = agentProperties;
            this.toolChatClient = toolChatClientFactory != null ? toolChatClientFactory.create() : null;
            this.toolCategories = toolCategories != null ? toolCategories : "";
        }

        @Override
        public String getName() {
            return "CHAT";
        }

        @Override
        public AgentCapability getCapability() {
            boolean hasMapTools = toolCategories.contains("map_navigation");
            boolean hasSearchTools = toolCategories.contains("information");
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
            return new AgentCapability(
                    "chat-generation",
                    desc,
                    strengths,
                    "text"
            );
        }

        @Override
        public AgentResult execute(AgentTask task) throws IOException {
            log.info("ChatAgent executing task: instruction={}", task.instruction());

            List<String> imageUrls = stringList(task.parameters().get("imageUrls"));
            List<ChatRequest.Message> history = historyList(task.parameters().get("history"));

            String response = canUseToolLoop()
                    ? chatWithTools(task.instruction(), imageUrls, history)
                    : chatClient.chatStream(task.instruction(), imageUrls, history);

            String motouGifPath = MotouTool.getAndClearLastGifPath();
            if (motouGifPath != null && (response == null || !response.contains("[MOTOU_GIF:"))) {
                response = "[MOTOU_GIF:" + motouGifPath + "]\n" + (response != null ? response : "");
            }

            LocalFileTools.PreparedFile localFile = LocalFileTools.peekPreparedFile();
            if (localFile != null && (response == null || !response.contains("[LOCAL_FILE:"))) {
                response = "[LOCAL_FILE:" + localFile.absolutePath() + "]\n" + (response != null ? response : "");
            }

            log.info("ChatAgent response: {} chars", response != null ? response.length() : 0);
            return AgentResult.success(task.taskId(), response, response);
        }

        private boolean canUseToolLoop() {
            return toolChatClient != null;
        }

        private String chatWithTools(String instruction, List<String> imageUrls,
                                      List<ChatRequest.Message> history) throws IOException {
            try {
                return toolChatClient.prompt()
                        .messages(toSpringAiMessages(instruction, imageUrls, history))
                        .toolContext(Map.of("imageBase64Urls", imageUrls != null ? imageUrls : List.of()))
                        .call()
                        .content();
            } catch (RuntimeException e) {
                log.warn("ChatAgent tool loop failed, falling back to legacy chat client: {}", e.getMessage());
                return chatClient.chatStream(instruction, imageUrls, history);
            }
        }

        private List<Message> toSpringAiMessages(String instruction, List<String> imageUrls,
                                                  List<ChatRequest.Message> history) {
            List<Message> messages = new ArrayList<>();
            String systemPrompt = agentProperties != null ? agentProperties.getSystemPrompt() : null;
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            if (history != null && !history.isEmpty()) {
                for (ChatRequest.Message historyMessage : history) {
                    Message message = toSpringAiMessage(historyMessage);
                    if (message != null) {
                        messages.add(message);
                    }
                }
            }
            String text = instruction != null ? instruction : "";
            if (imageUrls != null && !imageUrls.isEmpty()) {
                List<Media> mediaList = new ArrayList<>();
                for (String imageUrl : imageUrls) {
                    MimeType mimeType = detectMimeType(imageUrl);
                    mediaList.add(new Media(mimeType, URI.create(imageUrl)));
                }
                messages.add(UserMessage.builder().text(text).media(mediaList).build());
            } else {
                messages.add(new UserMessage(text));
            }
            return messages;
        }

        private static MimeType detectMimeType(String dataUrl) {
            if (dataUrl.contains("image/png")) return MimeTypeUtils.IMAGE_PNG;
            if (dataUrl.contains("image/jpg") || dataUrl.contains("image/jpeg")) return MimeTypeUtils.IMAGE_JPEG;
            if (dataUrl.contains("image/gif")) return MimeTypeUtils.IMAGE_GIF;
            if (dataUrl.contains("image/webp")) return MimeTypeUtils.parseMimeType("image/webp");
            return MimeTypeUtils.IMAGE_PNG;
        }

        private static Message toSpringAiMessage(ChatRequest.Message historyMessage) {
            if (historyMessage == null) {
                return null;
            }
            String content = contentAsText(historyMessage.getContent());
            if (content == null || content.isBlank()) {
                return null;
            }
            String role = historyMessage.getRole() != null
                    ? historyMessage.getRole().toLowerCase(Locale.ROOT)
                    : "";
            return switch (role) {
                case "system" -> new SystemMessage(content);
                case "assistant" -> new AssistantMessage(content);
                default -> new UserMessage(content);
            };
        }

        private static String contentAsText(Object content) {
            return content == null ? null : content instanceof String text ? text : String.valueOf(content);
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

        private static List<ChatRequest.Message> historyList(Object value) {
            if (value instanceof List<?> list) {
                return list.stream()
                        .filter(ChatRequest.Message.class::isInstance)
                        .map(ChatRequest.Message.class::cast)
                        .toList();
            }
            return List.of();
        }
    }

    public static class ImageGenAgent implements AgentUnit {

        private static final Logger log = LoggerFactory.getLogger(ImageGenAgent.class);
        private final ImageGenClient imageGenClient;

        public ImageGenAgent(ImageGenClient imageGenClient) {
            this.imageGenClient = imageGenClient;
        }

        @Override
        public String getName() {
            return "IMAGE_GEN";
        }

        @Override
        public AgentCapability getCapability() {
            return new AgentCapability(
                    "image-generation",
                    "Generates images from descriptive prompts.",
                    List.of("text-to-image", "illustration", "visual design"),
                    "image"
            );
        }

        @Override
        public AgentResult execute(AgentTask task) throws IOException {
            String prompt = task.instruction();
            log.info("ImageGenAgent executing task: prompt={}", prompt);

            byte[] imageBytes = imageGenClient.generate(prompt);
            GeneratedImage generatedImage = GeneratedImage.normalize(imageBytes, "generated");

            log.info("ImageGenAgent generated {} bytes, normalized to {} ({})",
                    imageBytes.length, generatedImage.fileName(), generatedImage.mediaType());
            return AgentResult.success(task.taskId(), generatedImage, "[image generated] prompt=" + prompt);
        }
    }

    public static class SpeechAgent implements AgentUnit {

        private static final Logger log = LoggerFactory.getLogger(SpeechAgent.class);
        private final TextToSpeechClient ttsClient;
        private final VoiceCatalog voiceCatalog;

        public SpeechAgent(TextToSpeechClient ttsClient, VoiceCatalog voiceCatalog) {
            this.ttsClient = ttsClient;
            this.voiceCatalog = voiceCatalog;
        }

        @Override
        public String getName() {
            return "SPEECH_GEN";
        }

        @Override
        public AgentCapability getCapability() {
            return new AgentCapability(
                    "speech-generation",
                    "Converts text into audio with configurable voice settings.",
                    List.of("tts", "narration", "audio"),
                    "voice"
            );
        }

        @Override
        public AgentResult execute(AgentTask task) throws IOException {
            String textToSpeak = resolveText(task);
            String voice = stringValue(task.parameters().get("voice"));
            String instruction = stringValue(task.parameters().get("instruction"));

            log.info("SpeechAgent executing task: textLen={}, voice={}, instruction={}",
                    textToSpeak.length(), voice, instruction);

            TtsResult ttsResult;
            if (instruction != null && !instruction.isBlank()) {
                String v = voice != null && !voice.isBlank() ? voice : voiceCatalog.defaultVoice().voiceId();
                ttsResult = ttsClient.synthesize(textToSpeak, v, instruction);
            } else if (voice != null && !voice.isBlank()) {
                ttsResult = ttsClient.synthesize(textToSpeak, voice);
            } else {
                ttsResult = ttsClient.synthesize(textToSpeak);
            }

            TtsOutput output = new TtsOutput(
                    ttsResult.audioBytes(), ttsResult.format(),
                    ttsResult.durationMs(), ttsResult.sampleRate());

            log.info("SpeechAgent synthesized {} bytes, duration={}ms",
                    ttsResult.audioBytes().length, ttsResult.durationMs());

            return AgentResult.success(task.taskId(), output, "[speech generated] " + textToSpeak);
        }

        private static String resolveText(AgentTask task) {
            String text = stringValue(task.parameters().get("text"));
            if (text != null && !text.isBlank()) {
                return text;
            }
            return task.instruction();
        }

        private static String stringValue(Object value) {
            return value instanceof String s && !s.isBlank() ? s : null;
        }

        public record TtsOutput(byte[] audioBytes, String format, int durationMs, int sampleRate) {}
    }

    public static class OrchestrationResult {

        public enum Status {
            NEEDS_CLARIFICATION,
            EXECUTE,
            COMPLETED
        }

        private final Status status;
        private final String reasoning;
        private final String clarificationQuestion;
        private final List<AgentTask> tasks;
        private final ModelReply finalReply;
        private final TaskScratchpad scratchpad;

        private OrchestrationResult(Builder builder) {
            this.status = builder.status;
            this.reasoning = builder.reasoning;
            this.clarificationQuestion = builder.clarificationQuestion;
            this.tasks = builder.tasks != null ? List.copyOf(builder.tasks) : List.of();
            this.finalReply = builder.finalReply;
            this.scratchpad = builder.scratchpad != null ? builder.scratchpad : new TaskScratchpad();
        }

        public Status status() { return status; }
        public String reasoning() { return reasoning; }
        public String clarificationQuestion() { return clarificationQuestion; }
        public List<AgentTask> tasks() { return tasks; }
        public ModelReply finalReply() { return finalReply; }
        public TaskScratchpad scratchpad() { return scratchpad; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private Status status;
            private String reasoning;
            private String clarificationQuestion;
            private List<AgentTask> tasks;
            private ModelReply finalReply;
            private TaskScratchpad scratchpad;

            public Builder status(Status status) { this.status = status; return this; }
            public Builder reasoning(String reasoning) { this.reasoning = reasoning; return this; }
            public Builder clarificationQuestion(String q) { this.clarificationQuestion = q; return this; }
            public Builder tasks(List<AgentTask> tasks) { this.tasks = tasks; return this; }
            public Builder finalReply(ModelReply reply) { this.finalReply = reply; return this; }
            public Builder scratchpad(TaskScratchpad sp) { this.scratchpad = sp; return this; }

            public OrchestrationResult build() {
                return new OrchestrationResult(this);
            }
        }
    }

    public interface OrchestratorAgent {
        OrchestrationResult plan(UserRequest request);

        OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest);
    }

    @ConfigurationProperties(prefix = "agent.orchestrator")
    public static class OrchestratorProperties {
        private int maxLoops = 5;
        private boolean clarificationEnabled = true;
        private boolean reflectionEnabled = true;

        public int getMaxLoops() { return maxLoops; }
        public void setMaxLoops(int maxLoops) { this.maxLoops = maxLoops; }

        public boolean isClarificationEnabled() { return clarificationEnabled; }
        public void setClarificationEnabled(boolean clarificationEnabled) { this.clarificationEnabled = clarificationEnabled; }

        public boolean isReflectionEnabled() { return reflectionEnabled; }
        public void setReflectionEnabled(boolean reflectionEnabled) { this.reflectionEnabled = reflectionEnabled; }
    }

    public static class TaskScratchpad {

        private final List<ExecutionRecord> records = new ArrayList<>();

        public void record(AgentTask task, AgentResult result) {
            records.add(new ExecutionRecord(task, result));
        }

        public List<ExecutionRecord> records() {
            return List.copyOf(records);
        }

        public String toReflectPrompt() {
            if (records.isEmpty()) {
                return "(no execution records)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Here are the subtask results:\n\n");
            for (int i = 0; i < records.size(); i++) {
                ExecutionRecord r = records.get(i);
                sb.append("--- Task ").append(i + 1).append(" ---\n");
                sb.append("Task ID: ").append(r.task.taskId()).append("\n");
                sb.append("Agent: ").append(r.task.agentType()).append("\n");
                sb.append("Instruction: ").append(r.task.instruction()).append("\n");
                sb.append("Status: ").append(r.result.status()).append("\n");
                if (r.result.rawOutput() != null && !r.result.rawOutput().isEmpty()) {
                    sb.append("Output: ").append(r.result.rawOutput()).append("\n");
                }
                if (r.result.errorMessage() != null) {
                    sb.append("Error: ").append(r.result.errorMessage()).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        public String lastSuccessfulChatText() {
            for (int i = records.size() - 1; i >= 0; i--) {
                ExecutionRecord r = records.get(i);
                if ("CHAT".equals(r.task().agentType())
                        && r.result().status() == AgentResult.Status.SUCCESS
                        && r.result().rawOutput() != null
                        && !r.result().rawOutput().isEmpty()) {
                    return r.result().rawOutput();
                }
            }
            return null;
        }

        public List<String> allSuccessfulChatTexts() {
            List<String> texts = new ArrayList<>();
            for (ExecutionRecord r : records) {
                if ("CHAT".equals(r.task().agentType())
                        && r.result().status() == AgentResult.Status.SUCCESS
                        && r.result().rawOutput() != null
                        && !r.result().rawOutput().isEmpty()) {
                    texts.add(r.result().rawOutput());
                }
            }
            return texts;
        }

        public String lastSuccessfulImageSummary() {
            for (int i = records.size() - 1; i >= 0; i--) {
                ExecutionRecord r = records.get(i);
                if ("IMAGE_GEN".equals(r.task().agentType())
                        && r.result().status() == AgentResult.Status.SUCCESS
                        && r.result().rawOutput() != null
                        && !r.result().rawOutput().isEmpty()) {
                    return r.result().rawOutput();
                }
            }
            return null;
        }

        public List<String> successfulImageDataUrls() {
            List<String> urls = new ArrayList<>();
            for (ExecutionRecord r : records) {
                if (!"IMAGE_GEN".equals(r.task().agentType())) {
                    continue;
                }
                if (r.result().status() != AgentResult.Status.SUCCESS) {
                    continue;
                }
                Object output = r.result().output();
                if (output instanceof GeneratedImage image && image.bytes().length > 0) {
                    urls.add(image.dataUrl());
                } else if (output instanceof byte[] bytes && bytes.length > 0) {
                    urls.add("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
                }
            }
            return urls;
        }

        public record ExecutionRecord(AgentTask task, AgentResult result) {}
    }

    public static class UserRequest {

        private final String userId;
        private final String text;
        private final List<String> imageBase64Urls;
        private final List<ChatRequest.Message> history;
        private final List<String> rememberedImageBase64Urls;
        private final String rememberedImageSummary;

        public UserRequest(String userId, String text, List<String> imageBase64Urls, List<ChatRequest.Message> history) {
            this(userId, text, imageBase64Urls, history, List.of(), null);
        }

        public UserRequest(String userId, String text, List<String> imageBase64Urls,
                           List<ChatRequest.Message> history, List<String> rememberedImageBase64Urls,
                           String rememberedImageSummary) {
            this.userId = userId;
            this.text = text;
            this.imageBase64Urls = imageBase64Urls != null ? List.copyOf(imageBase64Urls) : List.of();
            this.history = history != null ? List.copyOf(history) : List.of();
            this.rememberedImageBase64Urls = rememberedImageBase64Urls != null ? List.copyOf(rememberedImageBase64Urls) : List.of();
            this.rememberedImageSummary = rememberedImageSummary;
        }

        public String userId() { return userId; }
        public String text() { return text; }
        public List<String> imageBase64Urls() { return imageBase64Urls; }
        public List<ChatRequest.Message> history() { return history; }
        public List<String> rememberedImageBase64Urls() { return rememberedImageBase64Urls; }
        public String rememberedImageSummary() { return rememberedImageSummary; }
    }

    public static class OrchestratorAgentImpl implements OrchestratorAgent {

        private static final Logger log = LoggerFactory.getLogger(OrchestratorAgentImpl.class);
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private static final String PLAN_SYSTEM_PROMPT = """
                You are an orchestration model for a multi-agent assistant.
                Your system prompt contains the complete list of all available agent capabilities and TTS voices.
                Return JSON only, with no markdown and no extra text.

                Available agent units:
                %s

                Goals:
                1. Understand the user's intent from the current message and history.
                2. Decide whether clarification is required.
                3. Break the work into executable tasks when needed.

                Core principle — when to route vs answer directly:
                - You are the most knowledgeable component. Your prompt contains ALL capability/voice information.
                - If the user asks an informational question you can answer from your prompt context (e.g. available voices, what features are supported, how something works), return completed with your answer directly. Do NOT route these to CHAT.
                - You do NOT execute tools yourself. CHAT may use an internal tool-calling loop for runtime information, system/environment lookups, and future integrations.
                - If the request requires runtime information, external actions, integration data, or a capability that may exist as an internal tool, route to CHAT. Do not claim the system cannot do it just because the tool list is not shown to you; CHAT will answer normally if a tool exists and explain the limitation if no suitable tool exists.
                - Only route to sub-agents when their unique capability is needed:
                  * CHAT: content generation (creative writing, analysis of user-provided images, open-ended conversation, generating text that will be spoken, tool-assisted runtime tasks, AND generating file content — see file generation rules below. Check CHAT's internal tool categories in the agent list above to decide routing.)
                  * IMAGE_GEN: generating new static images only. NOT for GIFs, animated stickers, emoji packs, or 表情包 — those must route to CHAT (CHAT has media_generation tools).
                  * SPEECH_GEN: converting text to audio/speech

                Location / POI / Map / Navigation routing rules (CRITICAL):
                - When the user asks about locations, places, POI, nearby search, directions, routes, navigation, maps, geocoding, or coordinates, you MUST route to CHAT. CHAT has internal Amap (高德地图) tools that handle all these queries.
                - Explicit examples that MUST go to CHAT: "附近咖啡店", "XX在哪里", "搜索XX地点", "去XX怎么走", "从A到B的路线", "周边有什么餐厅", "查一下XX的地址", "导航到XX", "XX的坐标", any location/place name query.
                - Do NOT return completed for these queries — CHAT must handle them via its tool loop.
                - Do NOT suggest the user search manually or use a web browser — CHAT's internal tools will provide the answer directly.

                Reminder / Schedule / Timer routing rules (CRITICAL):
                - When the user asks to set a reminder, timer, alarm, schedule, or calendar event, you MUST route to CHAT. CHAT has internal automation tools (create_reminder, create_schedule_item, create_recurring_reminder, etc.) that handle all reminder and scheduling operations.
                - Explicit examples that MUST go to CHAT: "提醒我XX", "设置闹钟", "定时XX", "X分钟后叫我", "创建日程", "明天X点提醒", "每天X点提醒", "帮我记一下XX", "几点叫我", any reminder/timer/alarm/schedule request.
                - The system DOES support reminders — do NOT say it doesn't. Route to CHAT and let CHAT handle it.
                - Do NOT return completed for these queries — CHAT must handle them via its internal tool loop.

                DiDi Taxi / Ride-hailing routing rules (CRITICAL):
                - When the user asks to hail a taxi, call a car, or request a ride, you MUST route to CHAT. CHAT has internal DiDi tools (didi_taxi_estimate, didi_taxi_create_order, etc.).
                - CRITICAL: The DiDi taxi flow requires user confirmation on car type before creating an order. Your plan MUST only route ONE task to CHAT — CHAT will handle price estimation internally and present car options to the user. Do NOT plan a create_order task; the order happens in a separate conversation turn after the user explicitly selects a car type.
                - After an estimate-only CHAT task completes (status=SUCCESS, output contains car type options), the reflect phase MUST return completed — do NOT create follow-up tasks to auto-order.
                - Only create a create_order task when the user has explicitly confirmed a car type (e.g. "选特惠快车", "叫快车", "确认下单").
                - Explicit examples that MUST go to CHAT: "打车到XX", "帮我叫车", "叫个车去XX", "从A打车到B", "帮我打车", any ride-hailing/taxi request.

                File generation rules (via CHAT):
                - When the user asks to generate, export, save, download, or create a file (e.g. Markdown doc, report, summary, data export, weekly report, code file, etc.), route to CHAT.
                - CRITICAL: When creating a CHAT task for file generation, your instruction MUST explicitly include the output format requirement. Tell CHAT to wrap the file content in markers:
                  [FILE:filename.ext]
                  file content here...
                  [/FILE]
                - Example CHAT instruction for file generation: "请根据对话历史总结今天的内容，生成一个Markdown文档。你必须使用 [FILE:对话总结.md] 和 [/FILE] 标记包裹文件内容，[/FILE] 之后可附加简短说明。"
                - After the [/FILE] marker, the CHAT agent may include an optional plain-text response (e.g. "文件已生成"). This text will be sent alongside the file.
                - Supported file extensions: txt, md, json, csv, html, xml, log, docx. Choose the extension based on what the user asked for (e.g. .md for markdown documents, .docx for Word documents, .csv for tabular data, .json for structured data).
                - The filename should be descriptive and match the user's request (e.g. 周报.md, 数据分析报告.docx, 用户列表.csv).

                Input format — the user message may contain source annotations:
                - 【用户上传文件】filename（类型）: the user uploaded a file. The content that follows was extracted from this file.
                  * For documents (Word/PDF/txt): the text content and possibly embedded images were extracted.
                  * For audio files (MP3/WAV/etc): the text is a speech-to-text transcription result.
                  * ⚠️ If the annotation says "文件解析失败", processing failed. Tell the user directly (completed status) what went wrong. Do NOT route to any sub-agent.
                - 【用户语音消息】: the user sent a WeChat voice message. The text is the speech-to-text transcription.
                - 【用户发送了图片】: the user sent images directly (not from a file). Route to CHAT for visual understanding.
                - 【用户消息】: the user's own typed text message.

                Task routing rules:
                - Output one JSON object only.
                - Supported statuses: needs_clarification, execute, completed.
                - If the user wants an image and then a description or analysis of that generated image, plan multiple tasks.
                - When the user refers to prior context, use the conversation history.
                - For file uploads with successfully extracted content: treat the extracted text + images as the user's input and route to CHAT for analysis.
                - For file uploads with ⚠️ failure annotations: DO NOT route to CHAT. Return completed with a helpful message explaining the issue.
                - For voice messages: treat the transcription as user input and route to CHAT normally.
                - For SPEECH_GEN tasks:
                  * instruction MUST contain ONLY the raw text to speak. No narration, no stage directions, no "请朗读", no "停顿一秒", no markup of any kind. It will be sent verbatim to a TTS engine.
                  * IMPORTANT: If the text to speak depends on a prior CHAT task's output (e.g. "write a poem then recite it", "generate copy then read it aloud"), use the placeholder {{LAST_CHAT_TEXT}} as the instruction. The system will automatically replace it with the actual text from the last successful CHAT task.
                  * Detect the user's emotional state from conversation context.
                  * Choose a voice that matches the user's mood and the content (see voice list and mood mapping above).
                  * Set the voice via a "voice" parameter (use the exact voice ID).
                  * When the user explicitly asks for a specific voice name, dialect, or gender, use that.
                  * For Chinese dialects (Cantonese, Sichuan, Beijing, etc.) pick the matching dialect voice.
                - You may include an optional "parameters" object on each task.

                Output schemas:
                {"status":"needs_clarification","reasoning":"...","question":"..."}
                {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
                {"status":"completed","reasoning":"...","final_reply":"..."}
                """;

        private static final String REFLECT_SYSTEM_PROMPT = """
                You are reviewing completed subtask results for a multi-agent assistant.
                The prompt includes the ORIGINAL USER REQUEST first, followed by the subtask execution records.
                Return JSON only, with no markdown and no extra text.

                Available agent units:
                %s

                Decide one of the following:
                - completed: the current results are enough to answer the user
                - execute: more tasks are required

                Rules:
                - Compare the execution results against the original user request. Check whether EVERY part of the request has been addressed.
                - If the task results already satisfy ALL parts of the request, return completed.
                - If another step is needed, return execute with the next task list.
                - Prefer using the actual CHAT output as final_reply when it already answers the user.
                - If image or speech generation already succeeded, do not ask to regenerate unless there is a clear failure.
                - For new SPEECH_GEN tasks, follow the same voice selection rules as in planning.
                - For SPEECH_GEN tasks: instruction must be ONLY raw text to speak, no directions or markup.
                  * If the text to speak depends on a prior CHAT task's output, use the placeholder {{LAST_CHAT_TEXT}}. The system will automatically replace it with the actual CHAT output text.
                - If the user asked for a file and the CHAT output contains [FILE:...]...[/FILE] markers, the file was already generated successfully — do not route again.
                - DiDi Taxi rule: If the CHAT task output contains car type options / price estimates (e.g. "特惠快车", "快车", "价格预估") and the result is SUCCESS, the estimate flow is complete. Return completed — the user needs to choose a car type in the next turn. Do NOT create a follow-up create_order task unless the user explicitly said which car type they want (e.g. "选特惠快车", "叫快车"). Never auto-select a default car type for the user.
                - You may include an optional "parameters" object on each task.

                Output schemas:
                {"status":"completed","reasoning":"...","final_reply":"..."}
                {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
                """;

        private final RestTemplate restTemplate;
        private final String apiUrl;
        private final String apiKey;
        private final String model;
        private final String capabilitiesPrompt;

        public OrchestratorAgentImpl(AgentProperties props, AgentRegistry registry) {
            this.capabilitiesPrompt = registry.generateCapabilitiesPrompt();
            this.apiUrl = props.getIntentApiUrl() != null && !props.getIntentApiUrl().isEmpty()
                    ? props.getIntentApiUrl() : props.getApiUrl();
            this.apiKey = props.getIntentApiKey() != null && !props.getIntentApiKey().isEmpty()
                    ? props.getIntentApiKey() : props.getApiKey();
            this.model = props.getIntentModel() != null && !props.getIntentModel().isEmpty()
                    ? props.getIntentModel() : props.getModel();
            this.restTemplate = createRestTemplate(
                    props.getConnectTimeoutMs() > 0 ? props.getConnectTimeoutMs() : 5000,
                    props.getReadTimeoutMs() > 0 ? props.getReadTimeoutMs() : 30000);
        }

        private static RestTemplate createRestTemplate(int connectMs, int readMs) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectMs);
            factory.setReadTimeout(readMs);
            return new RestTemplate(factory);
        }

        @Override
        public OrchestrationResult plan(UserRequest request) {
            String text = request.text();
            if ((text == null || text.isBlank()) && request.imageBase64Urls().isEmpty()) {
                return fallbackPlan(text);
            }

            try {
                return doPlan(request);
            } catch (Exception e) {
                log.warn("Orchestrator plan() failed, using fallback. error={}", e.getMessage());
                return fallbackPlan(text);
            }
        }

        private OrchestrationResult doPlan(UserRequest request) {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", String.format(PLAN_SYSTEM_PROMPT, capabilitiesPrompt)));

            for (ChatRequest.Message msg : request.history()) {
                messages.add(Map.of("role", msg.getRole(), "content", String.valueOf(msg.getContent())));
            }

            StringBuilder userContent = new StringBuilder();
            userContent.append(request.text() != null ? request.text() : "");
            if (!request.imageBase64Urls().isEmpty()) {
                userContent.append("\n[user attached images: ").append(request.imageBase64Urls().size()).append("]");
            }
            if (request.rememberedImageSummary() != null && !request.rememberedImageSummary().isBlank()) {
                userContent.append("\n[remembered image summary] ").append(request.rememberedImageSummary());
            }
            messages.add(Map.of("role", "user", "content", userContent.toString()));

            String response = callModel(messages);
            return parsePlanResponse(response, request.text());
        }

        @Override
        public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            try {
                return doReflect(scratchpad, originalRequest);
            } catch (Exception e) {
                log.warn("Orchestrator reflect() failed, treating as completed. error={}", e.getMessage());
                return buildCompletedResult(scratchpad);
            }
        }

        private OrchestrationResult doReflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
            StringBuilder reflectContent = new StringBuilder();
            reflectContent.append("=== ORIGINAL USER REQUEST ===\n");
            reflectContent.append(originalRequest.text() != null ? originalRequest.text() : "");
            if (!originalRequest.imageBase64Urls().isEmpty()) {
                reflectContent.append("\n[user attached images: ").append(originalRequest.imageBase64Urls().size()).append("]");
            }
            if (originalRequest.rememberedImageSummary() != null && !originalRequest.rememberedImageSummary().isBlank()) {
                reflectContent.append("\n[remembered image summary] ").append(originalRequest.rememberedImageSummary());
            }
            reflectContent.append("\n\n");
            reflectContent.append(scratchpad.toReflectPrompt());

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", String.format(REFLECT_SYSTEM_PROMPT, capabilitiesPrompt)));
            messages.add(Map.of("role", "user", "content", reflectContent.toString()));

            String response = callModel(messages);
            return parseReflectResponse(response, scratchpad);
        }

        private String callModel(List<Map<String, Object>> messages) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.0);
            body.put("max_tokens", 2000);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<ChatResponse> response = restTemplate.postForEntity(apiUrl, entity, ChatResponse.class);
            ChatResponse chatResponse = response.getBody();

            if (chatResponse == null) {
                throw new RuntimeException("empty response from orchestrator model");
            }

            String content = chatResponse.extractContent();
            if (content == null || content.isBlank()) {
                throw new RuntimeException("empty content in orchestrator response");
            }

            log.debug("orchestrator raw response: {}", content);
            return content;
        }

        @SuppressWarnings("unchecked")
        private OrchestrationResult parsePlanResponse(String content, String originalText) {
            String json = extractJson(content);
            if (json == null) {
                log.warn("cannot extract JSON from orchestrator plan response: {}", content);
                return fallbackPlan(originalText);
            }

            try {
                Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
                String status = (String) map.get("status");
                String reasoning = (String) map.getOrDefault("reasoning", "");

                if ("needs_clarification".equals(status)) {
                    String question = (String) map.getOrDefault("question", "请再具体描述一下你的需求。");
                    return OrchestrationResult.builder()
                            .status(OrchestrationResult.Status.NEEDS_CLARIFICATION)
                            .reasoning(reasoning)
                            .clarificationQuestion(question)
                            .build();
                }

                if ("execute".equals(status)) {
                    List<AgentTask> tasks = parseTasks(map);
                    if (tasks.isEmpty()) {
                        return fallbackPlan(originalText);
                    }
                    return OrchestrationResult.builder()
                            .status(OrchestrationResult.Status.EXECUTE)
                            .reasoning(reasoning)
                            .tasks(tasks)
                            .build();
                }

                if ("completed".equals(status)) {
                    String finalReply = (String) map.getOrDefault("final_reply", "处理完成。");
                    return OrchestrationResult.builder()
                            .status(OrchestrationResult.Status.COMPLETED)
                            .reasoning(reasoning)
                            .finalReply(ModelReply.text(finalReply))
                            .build();
                }

                return fallbackPlan(originalText);
            } catch (Exception e) {
                log.warn("failed to parse orchestrator JSON: json={}, error={}", json, e.getMessage());
                return fallbackPlan(originalText);
            }
        }

        @SuppressWarnings("unchecked")
        private OrchestrationResult parseReflectResponse(String content, TaskScratchpad scratchpad) {
            String json = extractJson(content);
            if (json == null) {
                log.warn("cannot extract JSON from orchestrator reflect response: {}", content);
                return buildCompletedResult(scratchpad);
            }

            try {
                Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
                String status = (String) map.get("status");
                String reasoning = (String) map.getOrDefault("reasoning", "");

                if ("execute".equals(status)) {
                    List<AgentTask> tasks = parseTasks(map);
                    if (tasks.isEmpty()) {
                        return buildCompletedResult(scratchpad);
                    }
                    return OrchestrationResult.builder()
                            .status(OrchestrationResult.Status.EXECUTE)
                            .reasoning(reasoning)
                            .tasks(tasks)
                            .scratchpad(scratchpad)
                            .build();
                }

                String finalReply = (String) map.getOrDefault("final_reply", "任务已完成。");
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.COMPLETED)
                        .reasoning(reasoning)
                        .finalReply(ModelReply.text(finalReply))
                        .scratchpad(scratchpad)
                        .build();
            } catch (Exception e) {
                log.warn("failed to parse reflect JSON: json={}, error={}", json, e.getMessage());
                return buildCompletedResult(scratchpad);
            }
        }

        private List<AgentTask> parseTasks(Map<String, Object> map) {
            List<AgentTask> tasks = new ArrayList<>();
            Object tasksObj = map.get("tasks");
            if (!(tasksObj instanceof List<?> list)) {
                return tasks;
            }

            for (Object item : list) {
                if (!(item instanceof Map<?, ?> taskMap)) {
                    continue;
                }

                String agentType = taskMap.get("agent_type") instanceof String s ? s : null;
                String instruction = taskMap.get("instruction") instanceof String s ? s : null;
                if (agentType == null || agentType.isBlank() || instruction == null || instruction.isBlank()) {
                    continue;
                }

                Map<String, Object> parameters = Map.of();
                Object parametersObj = taskMap.get("parameters");
                if (parametersObj instanceof Map<?, ?> rawParams && !rawParams.isEmpty()) {
                    Map<String, Object> copied = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : rawParams.entrySet()) {
                        if (entry.getKey() instanceof String key) {
                            copied.put(key, entry.getValue());
                        }
                    }
                    parameters = Map.copyOf(copied);
                }

                tasks.add(new AgentTask(agentType, instruction, parameters));
            }

            return tasks;
        }

        private OrchestrationResult fallbackPlan(String text) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("fallback: orchestrator model unavailable, default to chat")
                    .tasks(List.of(new AgentTask("CHAT", text != null ? text : "", Map.of())))
                    .build();
        }

        private OrchestrationResult buildCompletedResult(TaskScratchpad scratchpad) {
            String reply = scratchpad.lastSuccessfulChatText();
            if (reply == null || reply.isBlank()) {
                if (!scratchpad.successfulImageDataUrls().isEmpty()) {
                    reply = "图片已生成。";
                } else {
                    reply = "任务已完成。";
                }
            }

            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.COMPLETED)
                    .reasoning("auto-completed from scratchpad")
                    .finalReply(ModelReply.text(reply))
                    .scratchpad(scratchpad)
                    .build();
        }

        static String extractJson(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }

            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                int start = trimmed.indexOf('\n');
                int end = trimmed.lastIndexOf("```");
                if (start < 0 || end <= start) {
                    return null;
                }
                trimmed = trimmed.substring(start + 1, end).trim();
            }

            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace <= firstBrace) {
                return null;
            }
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
    }

    public static class MessageRouter {

        private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
        private static final Pattern FILE_MARKER = Pattern.compile(
                "\\[FILE:(.+?)]\\r?\\n(.*?)\\r?\\n\\[/FILE]", Pattern.DOTALL);
        private static final Pattern MOTOU_GIF_MARKER = Pattern.compile(
                "\\[MOTOU_GIF:(.+?)]");
        private static final Pattern LOCAL_FILE_MARKER = Pattern.compile(
                "\\[LOCAL_FILE:(.+?)]");

        private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
        private final OrchestratorAgent orchestrator;
        private final AgentRegistry registry;
        private final ConversationMemory memory;
        private final VoiceCatalog voiceCatalog;
        private final DocumentService documentService;
        private final int maxLoops;
        private final boolean clarificationEnabled;
        private final boolean reflectionEnabled;

        public MessageRouter(OrchestratorAgent orchestrator, AgentRegistry registry, ConversationMemory memory,
                             VoiceCatalog voiceCatalog, DocumentService documentService,
                             OrchestratorProperties orchestratorProperties) {
            this.orchestrator = orchestrator;
            this.registry = registry;
            this.memory = memory;
            this.voiceCatalog = voiceCatalog;
            this.documentService = documentService;
            this.maxLoops = Math.max(1, orchestratorProperties.getMaxLoops());
            this.clarificationEnabled = orchestratorProperties.isClarificationEnabled();
            this.reflectionEnabled = orchestratorProperties.isReflectionEnabled();
        }

        public ModelReply route(String userId, String text, List<String> imageBase64Urls) throws IOException {
            ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
            lock.lock();
            DiDiTaxiTools.setCurrentUser(userId);
            LocationAuthorizationTools.setCurrentUser(userId);
            try {
            List<ChatRequest.Message> history = memory != null ? memory.getHistory(userId) : List.of();
            ImageMemory imageMemory = imageBase64Urls.isEmpty()
                    ? resolveImageMemory(userId)
                    : ImageMemory.empty();

            if (!imageBase64Urls.isEmpty() && memory != null) {
                memory.rememberImageContext(userId, imageBase64Urls, text);
            }

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

                if (!reflectionEnabled) {
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

            ModelReply finalReply = buildFinalReply(result);
            persistMemory(userId, text, result, finalReply);
            return finalReply;
            } finally {
                DiDiTaxiTools.clearCurrentUser();
                LocationAuthorizationTools.clearCurrentUser();
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

            if (isTaxiRequest(text)) {
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.EXECUTE)
                        .reasoning("taxi flow with current-location authorization support")
                        .tasks(List.of(new AgentTask(
                                "CHAT",
                                """
                                用户正在请求打车/叫车服务。

                                请优先遵守下面流程：
                                1. 如果用户没有给出明确的出发地坐标，且看起来可能不知道自己当前在哪，请优先调用 `request_phone_location_authorization` 生成手机定位授权链接，而不是只让用户手动描述当前位置。
                                2. 用户完成授权后，调用 `get_latest_authorized_phone_location` 读取最近一次手机定位结果，把它作为打车起点。
                                3. 终点如果是地名或地址，再按现有高德工具查询坐标。
                                4. 在起终点齐全前，不要直接调用滴滴预估或下单。
                                5. 预估后必须等待用户明确选择车型，下一轮才能继续下单。

                                用户原始请求：%s
                                """.formatted(text),
                                Map.of("flow", "taxi-location-auth"))))
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
                    byte[] fileBytes = documentService.generate(parsed.fileContent(), parsed.fileName());
                    filePayload = new ModelReply.FilePayload(fileBytes, parsed.fileName());
                }
            }

            List<ModelReply.ImagePayload> images = new ArrayList<>();
            if (textReply != null) {
                Matcher motouMatcher = MOTOU_GIF_MARKER.matcher(textReply);
                if (motouMatcher.find()) {
                    String gifPath = motouMatcher.group(1).trim();
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
                    "\u6717\u8bf5",
                    "\u5ff5\u7ed9\u6211\u542c",
                    "\u8bfb\u7ed9\u6211\u542c",
                    "\u8bb2\u7ed9\u6211\u542c",
                    "\u8bf4\u51fa\u6765",
                    "\u8bfb\u51fa\u6765",
                    "\u5e2e\u6211\u8bfb",
                    "\u5e2e\u6211\u5ff5",
                    "\u64ad\u62a5",
                    "\u5ff5\u4e00\u4e0b",
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

        private static boolean isTaxiRequest(String text) {
            return containsAny(text,
                    "打车",
                    "叫车",
                    "叫个车",
                    "滴滴",
                    "网约车",
                    "出租车",
                    "专车",
                    "快车",
                    "顺风车",
                    "代叫车",
                    "帮我叫车");
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
}
