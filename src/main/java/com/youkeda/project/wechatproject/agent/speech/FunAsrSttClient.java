package com.youkeda.project.wechatproject.agent.speech;

import com.youkeda.project.wechatproject.agent.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STT client backed by the OpenAI-compatible Qwen ASR API.
 * <p>
 * WeChat voice messages are downloaded as SILK bytes. The Qwen compatible API
 * supports inline audio content, so this client converts SILK to WAV when
 * needed and sends the audio as a Base64 data URI.
 */
public class FunAsrSttClient implements SpeechToTextClient {

    private static final Logger log = LoggerFactory.getLogger(FunAsrSttClient.class);

    private final RestTemplate restTemplate;
    private final AudioConverter audioConverter;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public FunAsrSttClient(SpeechProperties props, AudioConverter audioConverter) {
        this.apiKey = props.getApiKey();
        this.model = props.getStt().getModel();
        this.apiUrl = resolveApiUrl(props);
        this.audioConverter = audioConverter;
        this.restTemplate = createRestTemplate(10000, 180000);
    }

    private static RestTemplate createRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    private static String resolveApiUrl(SpeechProperties props) {
        String sttUrl = props.getStt().getApiUrl();
        if (sttUrl != null && !sttUrl.isBlank()) {
            return sttUrl;
        }

        String baseUrl = props.getApiUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl + "chat/completions";
        }
        return baseUrl + "/chat/completions";
    }

    @Override
    public String recognize(byte[] audioBytes, String format) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IOException("audio bytes are empty");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IOException("speech STT apiUrl is not configured");
        }

        PreparedAudio prepared = prepareAudio(audioBytes, format);
        String dataUri = toDataUri(prepared.bytes(), prepared.format());
        return transcribe(dataUri, prepared.format(), prepared.originalSize(), prepared.bytes().length);
    }

    private PreparedAudio prepareAudio(byte[] audioBytes, String format) throws IOException {
        String normalizedFormat = normalizeFormat(format);
        if ("silk".equals(normalizedFormat)) {
            byte[] wavBytes = audioConverter.silkToWav(audioBytes);
            return new PreparedAudio(wavBytes, "wav", audioBytes.length);
        }
        if (!"wav".equals(normalizedFormat) && !"pcm".equals(normalizedFormat)) {
            byte[] wavBytes = audioConverter.toWav(audioBytes);
            return new PreparedAudio(wavBytes, "wav", audioBytes.length);
        }
        return new PreparedAudio(audioBytes, normalizedFormat, audioBytes.length);
    }

    private String transcribe(String dataUri, String format, int originalSize, int payloadSize) throws IOException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(userMessage(dataUri)));
            body.put("stream", false);
            body.put("asr_options", Map.of(
                    "language", "zh",
                    "enable_itn", false
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            log.info("submitting STT request: url={}, model={}, format={}, originalSize={}B, payloadSize={}B",
                    apiUrl, model, format, originalSize, payloadSize);

            ResponseEntity<ChatResponse> response = restTemplate.postForEntity(apiUrl, entity, ChatResponse.class);
            ChatResponse result = response.getBody();
            if (result == null) {
                throw new IOException("empty STT response body");
            }

            String text = result.extractContent();
            if (text == null || text.isBlank()) {
                throw new IOException("STT response did not contain transcription text");
            }

            String normalizedText = text.trim();
            log.info("STT succeeded: text={}", normalizedText);
            return normalizedText;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("STT request failed", e);
            throw new IOException("speech recognition failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> userMessage(String dataUri) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(audioPart(dataUri)));
        return message;
    }

    private static Map<String, Object> audioPart(String dataUri) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "input_audio");
        part.put("input_audio", Map.of("data", dataUri));
        return part;
    }

    private static String toDataUri(byte[] audioBytes, String format) {
        String mimeType = mimeTypeFor(format);
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "wav";
        }
        return format.trim().toLowerCase(Locale.ROOT);
    }

    private static String mimeTypeFor(String format) {
        return switch (format) {
            case "wav", "pcm" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "ogg", "opus" -> "audio/ogg";
            case "webm" -> "audio/webm";
            case "flac" -> "audio/flac";
            case "amr" -> "audio/amr";
            case "aac", "m4a" -> "audio/aac";
            default -> "application/octet-stream";
        };
    }

    private record PreparedAudio(byte[] bytes, String format, int originalSize) {
    }
}
