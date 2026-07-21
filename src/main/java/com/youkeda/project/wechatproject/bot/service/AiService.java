package com.youkeda.project.wechatproject.bot.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * AI service namespace for model configuration, chat, multimodal calls, and image generation.
 */
public final class AiService {

    private AiService() {
    }

    @ConfigurationProperties(prefix = "agent.ai")
    public static class AgentProperties {

        private boolean enabled;
        private String apiKey;
        private String apiUrl;
        private String model;
        private double temperature;
        private int maxTokens;
        private String systemPrompt;
        private int connectTimeoutMs;
        private int readTimeoutMs;

        private boolean imageGenEnabled = true;
        private String imageGenApiUrl;
        private String imageGenApiKey;
        private String imageGenModel = "dall-e-3";
        private String imageGenSize = "1024x1024";
        private int imageGenN = 1;

        private String intentModel;
        private String intentApiUrl;
        private String intentApiKey;

        private int maxHistoryRounds = 10;
        private int memoryTtlMinutes = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

        public boolean isImageGenEnabled() { return imageGenEnabled; }
        public void setImageGenEnabled(boolean imageGenEnabled) { this.imageGenEnabled = imageGenEnabled; }

        public String getImageGenApiUrl() { return imageGenApiUrl; }
        public void setImageGenApiUrl(String imageGenApiUrl) { this.imageGenApiUrl = imageGenApiUrl; }

        public String getImageGenApiKey() { return imageGenApiKey; }
        public void setImageGenApiKey(String imageGenApiKey) { this.imageGenApiKey = imageGenApiKey; }

        public String getImageGenModel() { return imageGenModel; }
        public void setImageGenModel(String imageGenModel) { this.imageGenModel = imageGenModel; }

        public String getImageGenSize() { return imageGenSize; }
        public void setImageGenSize(String imageGenSize) { this.imageGenSize = imageGenSize; }

        public int getImageGenN() { return imageGenN; }
        public void setImageGenN(int imageGenN) { this.imageGenN = imageGenN; }

        public String getIntentModel() { return intentModel; }
        public void setIntentModel(String intentModel) { this.intentModel = intentModel; }

        public String getIntentApiUrl() { return intentApiUrl; }
        public void setIntentApiUrl(String intentApiUrl) { this.intentApiUrl = intentApiUrl; }

        public String getIntentApiKey() { return intentApiKey; }
        public void setIntentApiKey(String intentApiKey) { this.intentApiKey = intentApiKey; }

        public int getMaxHistoryRounds() { return maxHistoryRounds; }
        public void setMaxHistoryRounds(int maxHistoryRounds) { this.maxHistoryRounds = maxHistoryRounds; }

        public int getMemoryTtlMinutes() { return memoryTtlMinutes; }
        public void setMemoryTtlMinutes(int memoryTtlMinutes) { this.memoryTtlMinutes = memoryTtlMinutes; }
    }

    public interface AiModelClient {
        String chat(String userMessage, List<String> imageBase64Urls, List<ChatRequest.Message> history) throws IOException;

        default String chatStream(String userMessage, List<String> imageBase64Urls,
                                  List<ChatRequest.Message> history) throws IOException {
            return chat(userMessage, imageBase64Urls, history);
        }
    }

    public interface ImageGenClient {
        byte[] generate(String prompt) throws IOException;
    }

    public static class OpenAiCompatibleClient implements AiModelClient {

        private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private final AgentProperties props;
        private final RestTemplate restTemplate;

        public OpenAiCompatibleClient(AgentProperties props) {
            this.props = props;
            this.restTemplate = createRestTemplate(props);
        }

        private static RestTemplate createRestTemplate(AgentProperties props) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(props.getConnectTimeoutMs());
            factory.setReadTimeout(props.getReadTimeoutMs());
            return new RestTemplate(factory);
        }

        @Override
        public String chat(String userMessage, List<String> imageBase64Urls, List<ChatRequest.Message> history)
                throws IOException {
            ChatRequest request = buildRequest(userMessage, imageBase64Urls, history, false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(props.getApiKey());

            HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

            try {
                ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                        props.getApiUrl(), entity, ChatResponse.class);

                ChatResponse body = response.getBody();
                if (body == null) {
                    log.warn("empty response body from AI API");
                    return "抱歉，AI 服务返回为空，请稍后再试。";
                }

                String content = body.extractContent();
                if (content == null || content.isEmpty()) {
                    log.warn("no content in AI response, choices={}", body.getChoices());
                    return "抱歉，AI 未生成有效回复，请稍后再试。";
                }

                return content;

            } catch (RestClientException e) {
                String errorMsg = e.getMessage();
                log.error("AI API call failed: url={}, model={}, error={}", props.getApiUrl(), props.getModel(), errorMsg);

                boolean hasImages = imageBase64Urls != null && !imageBase64Urls.isEmpty();
                if (hasImages && errorMsg != null
                        && (errorMsg.contains("image") || errorMsg.contains("vision")
                        || errorMsg.contains("multimodal") || errorMsg.contains("400")
                        || errorMsg.contains("not supported"))) {
                    throw new IOException("该模型不支持图片输入，请切换到多模态模型（如 qwen-vl）。原始错误: " + errorMsg, e);
                }
                throw new IOException("AI service unavailable: " + errorMsg, e);
            }
        }

        @Override
        public String chatStream(String userMessage, List<String> imageBase64Urls,
                                 List<ChatRequest.Message> history) throws IOException {
            ChatRequest request = buildRequest(userMessage, imageBase64Urls, history, true);
            String requestJson = OBJECT_MAPPER.writeValueAsString(request);

            HttpURLConnectionBridge conn = HttpURLConnectionBridge.open(props.getApiUrl());
            conn.postJson(requestJson, props.getApiKey(), props.getConnectTimeoutMs(), props.getReadTimeoutMs());

            int status = conn.responseCode();
            if (status != 200) {
                String errBody = conn.errorBody();
                conn.disconnect();
                log.error("AI streaming API returned {}: {}", status, errBody);
                throw new IOException("AI service error: HTTP " + status + " - " + errBody);
            }

            StringBuilder fullContent = new StringBuilder();
            try (BufferedReader reader = conn.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data: ")) {
                        continue;
                    }

                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    String deltaContent = extractDeltaContent(data);
                    if (deltaContent != null) {
                        fullContent.append(deltaContent);
                    }
                }
            } finally {
                conn.disconnect();
            }

            if (fullContent.isEmpty()) {
                log.warn("no content in AI streaming response");
                return "抱歉，AI 未生成有效回复，请稍后再试。";
            }

            return fullContent.toString();
        }

        private ChatRequest buildRequest(String userMessage, List<String> imageBase64Urls,
                                         List<ChatRequest.Message> history, boolean stream) {
            ChatRequest.Message userMsg;
            if (imageBase64Urls != null && !imageBase64Urls.isEmpty()) {
                log.debug("building multimodal request with {} image(s)", imageBase64Urls.size());
                userMsg = new ChatRequest.Message("user", userMessage, imageBase64Urls);
            } else {
                userMsg = new ChatRequest.Message("user", userMessage);
            }

            List<ChatRequest.Message> messages = new ArrayList<>();
            messages.add(new ChatRequest.Message("system", props.getSystemPrompt()));
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
            messages.add(userMsg);

            return new ChatRequest(props.getModel(), messages, props.getTemperature(), props.getMaxTokens(), stream);
        }

        private static String extractDeltaContent(String dataJson) {
            try {
                JsonNode delta = OBJECT_MAPPER.readTree(dataJson).path("choices").path(0).path("delta");
                JsonNode content = delta.path("content");
                return content.isTextual() ? content.asText() : null;
            } catch (Exception e) {
                log.debug("failed to parse SSE data chunk: {}", dataJson, e);
                return null;
            }
        }
    }

    public static class DashScopeImageGenClient implements ImageGenClient {

        private static final Logger log = LoggerFactory.getLogger(DashScopeImageGenClient.class);

        public static final String DEFAULT_API_URL =
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation";

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        private static final int POLL_INTERVAL_MS = 2000;
        private static final int MAX_POLL_COUNT = 60;

        private final String apiUrl;
        private final String apiKey;
        private final String model;
        private final String size;
        private final int n;
        private final RestTemplate restTemplate;
        private final String tasksBaseUrl;

        public DashScopeImageGenClient(AgentProperties props) {
            this.apiUrl = props.getImageGenApiUrl() != null && !props.getImageGenApiUrl().isEmpty()
                    ? props.getImageGenApiUrl() : DEFAULT_API_URL;
            this.apiKey = props.getImageGenApiKey() != null && !props.getImageGenApiKey().isEmpty()
                    ? props.getImageGenApiKey() : props.getApiKey();
            this.model = props.getImageGenModel();
            this.size = props.getImageGenSize();
            this.n = props.getImageGenN();
            this.restTemplate = createRestTemplate(props);
            this.tasksBaseUrl = deriveTasksBaseUrl(this.apiUrl);
        }

        private static String deriveTasksBaseUrl(String apiUrl) {
            URI uri = URI.create(apiUrl);
            return uri.getScheme() + "://" + uri.getHost() + "/api/v1/tasks";
        }

        private static RestTemplate createRestTemplate(AgentProperties props) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(props.getConnectTimeoutMs());
            factory.setReadTimeout(180_000);
            return new RestTemplate(factory);
        }

        @Override
        public byte[] generate(String prompt) throws IOException {
            String taskId = submitTask(prompt);
            log.info("image gen task submitted: taskId={}", taskId);

            String imageUrl = pollTask(taskId);

            log.info("image gen task completed, downloading from: {}", imageUrl);
            return downloadImage(imageUrl);
        }

        private String submitTask(String prompt) throws IOException {
            Map<String, Object> requestBody = buildRequestBody(prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("X-DashScope-Async", "enable");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            try {
                ResponseEntity<TaskSubmitResponse> response = restTemplate.postForEntity(
                        apiUrl, entity, TaskSubmitResponse.class);

                TaskSubmitResponse body = response.getBody();
                if (body == null || body.output == null || body.output.taskId == null) {
                    throw new IOException("task submission returned no task_id");
                }
                return body.output.taskId;

            } catch (RestClientException e) {
                log.error("image gen task submission failed: url={}, model={}, error={}",
                        apiUrl, model, e.getMessage());
                throw new IOException("Image generation task submission failed: " + e.getMessage(), e);
            }
        }

        private String pollTask(String taskId) throws IOException {
            String taskUrl = tasksBaseUrl + "/" + taskId;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);

            for (int i = 0; i < MAX_POLL_COUNT; i++) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Polling interrupted", e);
                }

                try {
                    ResponseEntity<String> response = restTemplate.exchange(
                            taskUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

                    String raw = response.getBody();
                    if (raw == null || raw.isEmpty()) {
                        continue;
                    }

                    JsonNode output = OBJECT_MAPPER.readTree(raw).path("output");
                    if (output.isMissingNode() || output.isNull()) {
                        continue;
                    }

                    String status = textOrNull(output.path("task_status"));
                    log.debug("task {} status: {}", taskId, status);

                    if ("SUCCEEDED".equals(status)) {
                        String imageUrl = extractImageUrlFromOutput(output);
                        if (imageUrl == null) {
                            log.error("image URL not found in task response: {}", raw);
                            throw new IOException("task succeeded but no image URL found in response");
                        }
                        return imageUrl;
                    }

                    if ("FAILED".equals(status)) {
                        String msg = textOrNull(output.path("message"));
                        throw new IOException("image generation task failed: " + (msg != null ? msg : "unknown"));
                    }

                } catch (RestClientException e) {
                    log.warn("poll task {} failed (attempt {}): {}", taskId, i + 1, e.getMessage());
                }
            }

            throw new IOException("image generation timed out after " + (MAX_POLL_COUNT * POLL_INTERVAL_MS / 1000) + "s");
        }

        private Map<String, Object> buildRequestBody(String prompt) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("text", prompt);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", List.of(textPart));

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("messages", List.of(userMsg));

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("size", size);
            parameters.put("n", n);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", parameters);
            return body;
        }

        private static String extractImageUrlFromOutput(JsonNode output) {
            String url = extractImageFromChoices(output.path("choices"));
            if (url != null) {
                return url;
            }

            JsonNode results = output.path("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    url = textOrNull(result.path("url"));
                    if (url != null) {
                        return url;
                    }

                    String innerUrl = extractImageFromChoices(result.path("output").path("choices"));
                    if (innerUrl != null) {
                        return innerUrl;
                    }
                }
            }
            return null;
        }

        private static String extractImageFromChoices(JsonNode choices) {
            if (!choices.isArray()) {
                return null;
            }
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("message").path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode part : content) {
                    String image = textOrNull(part.path("image"));
                    if (image != null) {
                        return image;
                    }
                }
            }
            return null;
        }

        private static String textOrNull(JsonNode node) {
            if (node == null || !node.isTextual()) {
                return null;
            }
            String text = node.asText();
            return text == null || text.isBlank() ? null : text;
        }

        private byte[] downloadImage(String imageUrl) throws IOException {
            try (InputStream in = URI.create(imageUrl).toURL().openStream()) {
                return in.readAllBytes();
            }
        }

        public static class TaskSubmitResponse {
            @JsonProperty("output")
            public TaskOutput output;

            public static class TaskOutput {
                @JsonProperty("task_id")
                public String taskId;

                @JsonProperty("task_status")
                public String taskStatus;
            }
        }
    }

    public static class ChatRequest {

        @JsonProperty("model")
        private String model;

        @JsonProperty("messages")
        private List<Message> messages;

        @JsonProperty("temperature")
        private double temperature;

        @JsonProperty("max_tokens")
        private int maxTokens;

        @JsonProperty("stream")
        private boolean stream;

        public ChatRequest() {
        }

        public ChatRequest(String model, List<Message> messages, double temperature, int maxTokens) {
            this(model, messages, temperature, maxTokens, false);
        }

        public ChatRequest(String model, List<Message> messages, double temperature, int maxTokens, boolean stream) {
            this.model = model;
            this.messages = messages;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.stream = stream;
        }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }

        public static class Message {
            @JsonProperty("role")
            private String role;

            @JsonProperty("content")
            private Object content;

            public Message() {
            }

            public Message(String role, String textContent) {
                this.role = role;
                this.content = textContent;
            }

            public Message(String role, String text, List<String> imageBase64Urls) {
                this.role = role;
                List<ContentPart> parts = new ArrayList<>();
                if (text != null && !text.isEmpty()) {
                    parts.add(ContentPart.text(text));
                }
                for (String url : imageBase64Urls) {
                    parts.add(ContentPart.imageUrl(url));
                }
                this.content = parts;
            }

            public String getRole() { return role; }
            public void setRole(String role) { this.role = role; }

            public Object getContent() { return content; }
            public void setContent(Object content) { this.content = content; }
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        static class ContentPart {
            @JsonProperty("type")
            private String type;

            @JsonProperty("text")
            private String text;

            @JsonProperty("image_url")
            private ImageUrl imageUrl;

            ContentPart() {
            }

            static ContentPart text(String text) {
                ContentPart part = new ContentPart();
                part.type = "text";
                part.text = text;
                return part;
            }

            static ContentPart imageUrl(String url) {
                ContentPart part = new ContentPart();
                part.type = "image_url";
                part.imageUrl = new ImageUrl(url);
                return part;
            }

            public String getType() { return type; }
            public void setType(String type) { this.type = type; }

            public String getText() { return text; }
            public void setText(String text) { this.text = text; }

            public ImageUrl getImageUrl() { return imageUrl; }
            public void setImageUrl(ImageUrl imageUrl) { this.imageUrl = imageUrl; }
        }

        static class ImageUrl {
            @JsonProperty("url")
            private String url;

            ImageUrl() {
            }

            ImageUrl(String url) {
                this.url = url;
            }

            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
        }
    }

    public static class ChatResponse {

        @JsonProperty("id")
        private String id;

        @JsonProperty("choices")
        private List<Choice> choices;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public List<Choice> getChoices() { return choices; }
        public void setChoices(List<Choice> choices) { this.choices = choices; }

        public String extractContent() {
            if (choices == null || choices.isEmpty()) return null;
            Choice choice = choices.get(0);
            if (choice == null || choice.getMessage() == null) return null;
            return choice.getMessage().getContent();
        }

        public static class Choice {
            @JsonProperty("index")
            private int index;

            @JsonProperty("message")
            private Message message;

            @JsonProperty("finish_reason")
            private String finishReason;

            public int getIndex() { return index; }
            public void setIndex(int index) { this.index = index; }

            public Message getMessage() { return message; }
            public void setMessage(Message message) { this.message = message; }

            public String getFinishReason() { return finishReason; }
            public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
        }

        public static class Message {
            @JsonProperty("role")
            private String role;

            @JsonProperty("content")
            private String content;

            public String getRole() { return role; }
            public void setRole(String role) { this.role = role; }

            public String getContent() { return content; }
            public void setContent(String content) { this.content = content; }
        }
    }

    public record GeneratedImage(byte[] bytes, String fileName, String mediaType) {

        private static final int MAX_DIMENSION = 1024;
        private static final float JPEG_QUALITY = 0.9f;

        public GeneratedImage {
            Objects.requireNonNull(bytes, "bytes must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
        }

        public static GeneratedImage normalize(byte[] rawBytes, String baseName) throws IOException {
            Objects.requireNonNull(rawBytes, "rawBytes must not be null");

            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (decoded == null) {
                String extension = detectExtension(rawBytes);
                String type = detectMediaType(rawBytes);
                return new GeneratedImage(rawBytes, ensureExtension(baseName, extension), type);
            }

            BufferedImage scaled = resizeIfNeeded(decoded);
            boolean hasAlpha = scaled.getColorModel().hasAlpha();
            if (hasAlpha) {
                return new GeneratedImage(writePng(scaled), ensureExtension(baseName, "png"), "image/png");
            }
            return new GeneratedImage(writeJpeg(scaled), ensureExtension(baseName, "jpg"), "image/jpeg");
        }

        public String dataUrl() {
            return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }

        private static BufferedImage resizeIfNeeded(BufferedImage src) {
            int width = src.getWidth();
            int height = src.getHeight();
            int max = Math.max(width, height);
            if (max <= MAX_DIMENSION) {
                return src;
            }

            double ratio = (double) MAX_DIMENSION / max;
            int newWidth = Math.max(1, (int) Math.round(width * ratio));
            int newHeight = Math.max(1, (int) Math.round(height * ratio));

            int imageType = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage scaled = new BufferedImage(newWidth, newHeight, imageType);
            Graphics2D g = scaled.createGraphics();
            g.drawImage(src, 0, 0, newWidth, newHeight, null);
            g.dispose();
            return scaled;
        }

        private static byte[] writePng(BufferedImage image) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("failed to encode image as png");
            }
            return out.toByteArray();
        }

        private static byte[] writeJpeg(BufferedImage image) throws IOException {
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new IOException("jpeg writer not available");
            }

            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }

            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(rgb, null, null), param);
            writer.dispose();
            return out.toByteArray();
        }

        private static String detectExtension(byte[] bytes) {
            if (startsWith(bytes, (byte) 0x89, 'P', 'N', 'G')) {
                return "png";
            }
            if (startsWith(bytes, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) {
                return "jpg";
            }
            if (startsWith(bytes, 'G', 'I', 'F', '8')) {
                return "gif";
            }
            if (startsWith(bytes, 'R', 'I', 'F', 'F') && containsAt(bytes, 8, 'W', 'E', 'B', 'P')) {
                return "webp";
            }
            return "bin";
        }

        private static String detectMediaType(byte[] bytes) {
            return switch (detectExtension(bytes).toLowerCase(Locale.ROOT)) {
                case "png" -> "image/png";
                case "jpg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                default -> "application/octet-stream";
            };
        }

        private static boolean startsWith(byte[] bytes, int... magic) {
            if (bytes.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if ((bytes[i] & 0xFF) != (magic[i] & 0xFF)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean containsAt(byte[] bytes, int offset, int... magic) {
            if (bytes.length < offset + magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if ((bytes[offset + i] & 0xFF) != (magic[i] & 0xFF)) {
                    return false;
                }
            }
            return true;
        }

        private static String ensureExtension(String baseName, String extension) {
            String normalizedBase = (baseName == null || baseName.isBlank()) ? "generated" : baseName;
            int dotIndex = normalizedBase.lastIndexOf('.');
            if (dotIndex > 0) {
                normalizedBase = normalizedBase.substring(0, dotIndex);
            }
            return normalizedBase + "." + extension;
        }
    }

    private static class HttpURLConnectionBridge {
        private final java.net.HttpURLConnection conn;

        private HttpURLConnectionBridge(java.net.HttpURLConnection conn) {
            this.conn = conn;
        }

        static HttpURLConnectionBridge open(String url) throws IOException {
            return new HttpURLConnectionBridge((java.net.HttpURLConnection) URI.create(url).toURL().openConnection());
        }

        void postJson(String requestJson, String apiKey, int connectMs, int readMs) throws IOException {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        int responseCode() throws IOException {
            return conn.getResponseCode();
        }

        String errorBody() throws IOException {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errBody = new StringBuilder();
                String line;
                while ((line = errReader.readLine()) != null) {
                    errBody.append(line);
                }
                return errBody.toString();
            }
        }

        BufferedReader inputReader() throws IOException {
            return new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        }

        void disconnect() {
            conn.disconnect();
        }
    }
}
