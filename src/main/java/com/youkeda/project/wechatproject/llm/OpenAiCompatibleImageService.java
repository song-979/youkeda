package com.youkeda.project.wechatproject.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.youkeda.project.wechatproject.config.LlmProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@Service
public class OpenAiCompatibleImageService {

    private final RestTemplate restTemplate;
    private final LlmProperties llmProperties;

    public OpenAiCompatibleImageService(RestTemplate restTemplate, LlmProperties llmProperties) {
        this.restTemplate = restTemplate;
        this.llmProperties = llmProperties;
    }

    public boolean isAvailable() {
        return llmProperties.isImageGenerationConfigured();
    }

    public GeneratedImage generateImage(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("Image prompt must not be empty.");
        }

        if (isWanModel(llmProperties.getImageGenerationModel())) {
            return generateWithWan(prompt);
        }

        String requestUrl = buildImagesGenerationsUrl(llmProperties.getBaseUrl());
        ImageGenerationRequest request = new ImageGenerationRequest();
        request.setModel(llmProperties.getImageGenerationModel().trim());
        request.setPrompt(prompt.trim());
        request.setN(1);
        request.setSize(normalizeSize(llmProperties.getImageGenerationSize()));
        request.setResponseFormat("b64_json");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmProperties.getApiKey().trim());

        try {
            ResponseEntity<ImageGenerationResponse> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.POST,
                    new HttpEntity<ImageGenerationRequest>(request, headers),
                    ImageGenerationResponse.class
            );

            return extractGeneratedImage(response.getBody());
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(
                    "Image generation failed with status " + e.getRawStatusCode() + ": " + abbreviate(e.getResponseBodyAsString()),
                    e
            );
        } catch (Exception e) {
            throw new IllegalStateException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private GeneratedImage generateWithWan(String prompt) {
        String requestUrl = buildWanGenerationUrl(llmProperties.getBaseUrl());
        WanGenerationRequest request = new WanGenerationRequest();
        request.setModel(llmProperties.getImageGenerationModel().trim());
        request.setInput(WanInput.singleTextPrompt(prompt.trim()));
        request.setParameters(WanParameters.defaultParameters(normalizeWanSize(llmProperties.getImageGenerationSize())));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmProperties.getApiKey().trim());

        try {
            ResponseEntity<WanGenerationResponse> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.POST,
                    new HttpEntity<WanGenerationRequest>(request, headers),
                    WanGenerationResponse.class
            );
            return extractWanGeneratedImage(response.getBody());
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(
                    "Wan image generation failed with status " + e.getRawStatusCode() + ": " + abbreviate(e.getResponseBodyAsString()),
                    e
            );
        } catch (Exception e) {
            throw new IllegalStateException("Wan image generation failed: " + e.getMessage(), e);
        }
    }

    private GeneratedImage extractGeneratedImage(ImageGenerationResponse response) {
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new IllegalStateException("Image generation response did not contain image data.");
        }

        ImageData first = response.getData().get(0);
        if (first == null) {
            throw new IllegalStateException("Image generation response returned an empty item.");
        }

        if (StringUtils.hasText(first.getB64Json())) {
            byte[] bytes = Base64.getDecoder().decode(first.getB64Json().trim());
            return new GeneratedImage(bytes, "generated-image.png", first.getRevisedPrompt());
        }

        if (StringUtils.hasText(first.getUrl())) {
            ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                    URI.create(first.getUrl().trim()),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    byte[].class
            );
            byte[] bytes = downloadResponse.getBody();
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("Image generation URL did not return image bytes.");
            }
            return new GeneratedImage(bytes, "generated-image.png", first.getRevisedPrompt());
        }

        throw new IllegalStateException("Image generation response did not contain b64_json or url.");
    }

    private String buildImagesGenerationsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.endsWith("/images/generations")) {
            return normalized;
        }
        if (normalized.matches("^https?://[^/]+\\.maas\\.aliyuncs\\.com$")) {
            return normalized + "/compatible-mode/v1/images/generations";
        }
        if (normalized.endsWith("/compatible-mode/v1") || normalized.endsWith("/v1")) {
            return normalized + "/images/generations";
        }
        return normalized + "/v1/images/generations";
    }

    private String buildWanGenerationUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.endsWith("/api/v1/services/aigc/multimodal-generation/generation")) {
            return normalized;
        }
        if (normalized.endsWith("/compatible-mode/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/compatible-mode/v1".length());
        } else if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        return normalized + "/api/v1/services/aigc/multimodal-generation/generation";
    }

    private boolean isWanModel(String model) {
        return StringUtils.hasText(model) && model.trim().startsWith("wan2.7-image");
    }

    private GeneratedImage extractWanGeneratedImage(WanGenerationResponse response) {
        if (response == null
                || response.getOutput() == null
                || response.getOutput().getChoices() == null
                || response.getOutput().getChoices().isEmpty()) {
            throw new IllegalStateException("Wan image generation response did not contain choices.");
        }

        WanChoice firstChoice = response.getOutput().getChoices().get(0);
        if (firstChoice == null
                || firstChoice.getMessage() == null
                || firstChoice.getMessage().getContent() == null
                || firstChoice.getMessage().getContent().isEmpty()) {
            throw new IllegalStateException("Wan image generation response did not contain image content.");
        }

        for (WanContentItem item : firstChoice.getMessage().getContent()) {
            if (item != null && StringUtils.hasText(item.getImage())) {
                ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                        URI.create(item.getImage().trim()),
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        byte[].class
                );
                byte[] bytes = downloadResponse.getBody();
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException("Wan image URL did not return image bytes.");
                }
                return new GeneratedImage(bytes, "generated-image.png", null);
            }
        }

        throw new IllegalStateException("Wan image generation response did not contain downloadable image output.");
    }

    private String normalizeWanSize(String configuredSize) {
        if (!StringUtils.hasText(configuredSize)) {
            return "1K";
        }

        String normalized = configuredSize.trim().toUpperCase();
        if ("1024X1024".equals(normalized)) {
            return "1K";
        }
        if ("2048X2048".equals(normalized)) {
            return "2K";
        }
        if ("4096X4096".equals(normalized)) {
            return "4K";
        }
        return normalized;
    }

    private String normalizeSize(String configuredSize) {
        if (!StringUtils.hasText(configuredSize)) {
            return "1024x1024";
        }
        return configuredSize.trim().replace('*', 'x');
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300) + "...";
    }

    public static class GeneratedImage {

        private final byte[] imageBytes;
        private final String fileName;
        private final String revisedPrompt;

        public GeneratedImage(byte[] imageBytes, String fileName, String revisedPrompt) {
            this.imageBytes = imageBytes;
            this.fileName = fileName;
            this.revisedPrompt = revisedPrompt;
        }

        public byte[] getImageBytes() {
            return imageBytes;
        }

        public String getFileName() {
            return fileName;
        }

        public String getRevisedPrompt() {
            return revisedPrompt;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageGenerationRequest {

        private String model;
        private String prompt;
        private Integer n = 1;
        private String size = "1024x1024";
        @JsonProperty("response_format")
        private String response_format = "b64_json";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public Integer getN() {
            return n;
        }

        public void setN(Integer n) {
            this.n = n;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }

        public String getResponseFormat() {
            return response_format;
        }

        public void setResponseFormat(String responseFormat) {
            this.response_format = responseFormat;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageGenerationResponse {

        private List<ImageData> data = Collections.emptyList();

        public List<ImageData> getData() {
            return data;
        }

        public void setData(List<ImageData> data) {
            this.data = data;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageData {

        @JsonProperty("b64_json")
        private String b64_json;
        private String url;
        @JsonProperty("revised_prompt")
        private String revised_prompt;

        public String getB64Json() {
            return b64_json;
        }

        public void setB64Json(String b64Json) {
            this.b64_json = b64Json;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getRevisedPrompt() {
            return revised_prompt;
        }

        public void setRevisedPrompt(String revisedPrompt) {
            this.revised_prompt = revisedPrompt;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanGenerationRequest {

        private String model;
        private WanInput input;
        private WanParameters parameters;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public WanInput getInput() {
            return input;
        }

        public void setInput(WanInput input) {
            this.input = input;
        }

        public WanParameters getParameters() {
            return parameters;
        }

        public void setParameters(WanParameters parameters) {
            this.parameters = parameters;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanInput {

        private List<WanMessage> messages = Collections.emptyList();

        public static WanInput singleTextPrompt(String prompt) {
            WanInput input = new WanInput();
            input.setMessages(Collections.singletonList(WanMessage.singleTextPrompt(prompt)));
            return input;
        }

        public List<WanMessage> getMessages() {
            return messages;
        }

        public void setMessages(List<WanMessage> messages) {
            this.messages = messages;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanMessage {

        private String role;
        private List<WanContentItem> content = Collections.emptyList();

        public static WanMessage singleTextPrompt(String prompt) {
            WanMessage message = new WanMessage();
            message.setRole("user");
            message.setContent(Collections.singletonList(WanContentItem.text(prompt)));
            return message;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public List<WanContentItem> getContent() {
            return content;
        }

        public void setContent(List<WanContentItem> content) {
            this.content = content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanContentItem {

        private String text;
        private String image;
        private String type;

        public static WanContentItem text(String prompt) {
            WanContentItem item = new WanContentItem();
            item.setText(prompt);
            return item;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanParameters {

        private String size = "1K";
        private Integer n = 1;
        private Boolean watermark = false;
        @JsonProperty("thinking_mode")
        private Boolean thinkingMode = true;

        public static WanParameters defaultParameters(String size) {
            WanParameters parameters = new WanParameters();
            parameters.setSize(size);
            return parameters;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }

        public Integer getN() {
            return n;
        }

        public void setN(Integer n) {
            this.n = n;
        }

        public Boolean getWatermark() {
            return watermark;
        }

        public void setWatermark(Boolean watermark) {
            this.watermark = watermark;
        }

        public Boolean getThinkingMode() {
            return thinkingMode;
        }

        public void setThinkingMode(Boolean thinkingMode) {
            this.thinkingMode = thinkingMode;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanGenerationResponse {

        private WanOutput output;

        public WanOutput getOutput() {
            return output;
        }

        public void setOutput(WanOutput output) {
            this.output = output;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanOutput {

        private List<WanChoice> choices = Collections.emptyList();

        public List<WanChoice> getChoices() {
            return choices;
        }

        public void setChoices(List<WanChoice> choices) {
            this.choices = choices;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanChoice {

        private WanAssistantMessage message;

        public WanAssistantMessage getMessage() {
            return message;
        }

        public void setMessage(WanAssistantMessage message) {
            this.message = message;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WanAssistantMessage {

        private List<WanContentItem> content = Collections.emptyList();

        public List<WanContentItem> getContent() {
            return content;
        }

        public void setContent(List<WanContentItem> content) {
            this.content = content;
        }
    }
}
