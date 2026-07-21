package com.youkeda.project.wechatproject.agent.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URL;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class Qwen3TtsFlashClient implements TextToSpeechClient {

    private static final Logger log = LoggerFactory.getLogger(Qwen3TtsFlashClient.class);

    private static final String TTS_PATH = "/api/v1/services/aigc/multimodal-generation/generation";

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String format;
    private final int sampleRate;

    public Qwen3TtsFlashClient(SpeechProperties props) {
        this.apiKey = props.getApiKey();
        this.model = props.getTts().getModel();
        this.voice = props.getTts().getVoice();
        this.format = props.getTts().getFormat();
        this.sampleRate = props.getTts().getSampleRate();
        this.apiUrl = resolveTtsApiUrl(props);
        this.restTemplate = createRestTemplate(10000, 120000);
    }

    private static String resolveTtsApiUrl(SpeechProperties props) {
        String ttsUrl = props.getTts().getApiUrl();
        if (ttsUrl != null && !ttsUrl.isBlank()) {
            return ttsUrl;
        }
        String baseUrl = props.getApiUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URL url = new URL(baseUrl);
            return url.getProtocol() + "://" + url.getHost() + TTS_PATH;
        } catch (Exception e) {
            log.warn("failed to parse base apiUrl, TTS will be unavailable: {}", baseUrl, e);
            return null;
        }
    }

    private static RestTemplate createRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    @Override
    public TtsResult synthesize(String text) throws IOException {
        return synthesize(text, null);
    }

    @Override
    public TtsResult synthesize(String text, String requestedVoice) throws IOException {
        return synthesize(text, requestedVoice, null);
    }

    @Override
    public TtsResult synthesize(String text, String requestedVoice, String instruction) throws IOException {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IOException("TTS apiUrl is not configured");
        }

        String voiceToUse = requestedVoice != null && !requestedVoice.isBlank() ? requestedVoice : voice;

        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("text", text);
            input.put("voice", voiceToUse);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            log.info("calling Qwen3-TTS-Flash: url={}, model={}, voice={}, textLen={}",
                    apiUrl, model, voiceToUse, text.length());

            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            byte[] audioBytes = extractAudioBytes(response.getBody());

            if (audioBytes == null || audioBytes.length == 0) {
                throw new IOException("TTS returned empty audio");
            }

            log.info("Qwen3-TTS-Flash generated audio: {}B", audioBytes.length);
            return new TtsResult(audioBytes, "wav", 24000, 0);

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qwen3-TTS-Flash TTS failed", e);
            throw new IOException("语音生成失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] extractAudioBytes(Map<String, Object> responseBody) throws IOException {
        if (responseBody == null) {
            throw new IOException("empty TTS response body");
        }

        Object outputObj = responseBody.get("output");
        if (outputObj instanceof Map output) {
            Object audioObj = output.get("audio");
            if (audioObj instanceof Map audio) {
                Object data = audio.get("data");
                if (data instanceof String base64 && !base64.isEmpty()) {
                    log.info("TTS audio from base64 data: {} chars", base64.length());
                    return Base64.getDecoder().decode(base64);
                }
                Object url = audio.get("url");
                if (url instanceof String urlStr && !urlStr.isEmpty()) {
                    log.info("TTS audio downloading from OSS: {}", urlStr);
                    try (java.io.InputStream is = new java.net.URL(urlStr).openStream()) {
                        return is.readAllBytes();
                    }
                }
            }
        }

        throw new IOException("unexpected TTS response format: " + responseBody);
    }
}
