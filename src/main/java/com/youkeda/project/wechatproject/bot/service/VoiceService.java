package com.youkeda.project.wechatproject.bot.service;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Voice service namespace for STT, TTS, audio conversion, and voice catalog metadata.
 */
public final class VoiceService {

    private VoiceService() {
    }

    @ConfigurationProperties(prefix = "agent.speech")
    public static class SpeechProperties {

        private String apiKey;
        private String apiUrl;
        private boolean enabled = true;
        private Stt stt = new Stt();
        private Tts tts = new Tts();

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Stt getStt() { return stt; }
        public void setStt(Stt stt) { this.stt = stt; }

        public Tts getTts() { return tts; }
        public void setTts(Tts tts) { this.tts = tts; }

        public static class Stt {
            private boolean enabled = true;
            private String model = "qwen3-asr-flash";
            private String apiUrl;
            private String ffmpegPath = "ffmpeg";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }

            public String getApiUrl() { return apiUrl; }
            public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

            public String getFfmpegPath() { return ffmpegPath; }
            public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }
        }

        public static class Tts {
            private boolean enabled = true;
            private String model = "qwen-audio-3.0-tts-flash";
            private String voice = "longanhuan_v3.6";
            private String format = "wav";
            private int sampleRate = 24000;
            private String apiUrl;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }

            public String getVoice() { return voice; }
            public void setVoice(String voice) { this.voice = voice; }

            public String getFormat() { return format; }
            public void setFormat(String format) { this.format = format; }

            public int getSampleRate() { return sampleRate; }
            public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

            public String getApiUrl() { return apiUrl; }
            public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        }
    }

    public interface SpeechToTextClient {
        String recognize(byte[] audioBytes, String format) throws IOException;
    }

    public interface TextToSpeechClient {
        TtsResult synthesize(String text) throws IOException;

        default TtsResult synthesize(String text, String voice) throws IOException {
            return synthesize(text);
        }

        default TtsResult synthesize(String text, String voice, String instruction) throws IOException {
            return synthesize(text, voice);
        }
    }

    public record TtsResult(byte[] audioBytes, String format, int sampleRate, int durationMs) {
    }

    public static class AudioConverter {

        private static final Logger log = LoggerFactory.getLogger(AudioConverter.class);
        private final String ffmpegPath;

        public AudioConverter(String ffmpegPath) {
            this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
        }

        public byte[] silkToWav(byte[] silkBytes) throws IOException {
            Path silkFile = null;
            Path wavFile = null;
            try {
                silkFile = Files.createTempFile("voice_", ".silk");
                wavFile = Files.createTempFile("voice_", ".wav");
                Files.write(silkFile, silkBytes);

                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",
                        "-f", "silk",
                        "-i", silkFile.toString(),
                        "-ar", "16000",
                        "-ac", "1",
                        "-f", "wav",
                        wavFile.toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
                try (InputStream is = process.getInputStream()) {
                    is.transferTo(ffmpegOutput);
                }
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                    log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                    throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
                }

                byte[] wavBytes = Files.readAllBytes(wavFile);
                log.info("SILK to WAV converted: {}B -> {}B", silkBytes.length, wavBytes.length);
                return wavBytes;

            } catch (InvalidPathException e) {
                throw new IOException("FFmpeg path is invalid: " + ffmpegPath, e);
            } catch (IOException e) {
                if (isMissingCommand(e)) {
                    throw new IOException("FFmpeg not found. Install FFmpeg or set agent.speech.stt.ffmpeg-path to the executable path.", e);
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("audio conversion interrupted", e);
            } finally {
                deleteTempFile(silkFile);
                deleteTempFile(wavFile);
            }
        }

        public byte[] wavToMp3(byte[] wavBytes) throws IOException {
            Path wavFile = null;
            Path mp3File = null;
            try {
                wavFile = Files.createTempFile("tts_", ".wav");
                mp3File = Files.createTempFile("tts_", ".mp3");
                Files.write(wavFile, wavBytes);

                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",
                        "-i", wavFile.toString(),
                        "-codec:a", "libmp3lame",
                        "-b:a", "128k",
                        mp3File.toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
                try (InputStream is = process.getInputStream()) {
                    is.transferTo(ffmpegOutput);
                }
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                    log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                    throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
                }

                byte[] mp3Bytes = Files.readAllBytes(mp3File);
                log.info("WAV to MP3 converted: {}B -> {}B", wavBytes.length, mp3Bytes.length);
                return mp3Bytes;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("audio conversion interrupted", e);
            } finally {
                deleteTempFile(wavFile);
                deleteTempFile(mp3File);
            }
        }

        public byte[] toWav(byte[] audioBytes) throws IOException {
            Path srcFile = null;
            Path wavFile = null;
            try {
                srcFile = Files.createTempFile("conv_", ".audio");
                wavFile = Files.createTempFile("conv_", ".wav");
                Files.write(srcFile, audioBytes);

                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",
                        "-i", srcFile.toString(),
                        "-ar", "16000",
                        "-ac", "1",
                        "-f", "wav",
                        wavFile.toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
                try (InputStream is = process.getInputStream()) {
                    is.transferTo(ffmpegOutput);
                }
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                    log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                    throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
                }

                byte[] wavBytes = Files.readAllBytes(wavFile);
                log.info("Audio to WAV converted: {}B -> {}B", audioBytes.length, wavBytes.length);
                return wavBytes;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("audio conversion interrupted", e);
            } finally {
                deleteTempFile(srcFile);
                deleteTempFile(wavFile);
            }
        }

        private static void deleteTempFile(Path file) {
            if (file == null) {
                return;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }

        private static boolean isMissingCommand(IOException e) {
            String message = e.getMessage();
            return message != null
                    && (message.contains("CreateProcess error=2")
                    || message.contains("Cannot run program")
                    || message.contains("No such file or directory"));
        }
    }

    public static class FunAsrSttClient implements SpeechToTextClient {

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

    public static class Qwen3TtsFlashClient implements TextToSpeechClient {

        private static final Logger log = LoggerFactory.getLogger(Qwen3TtsFlashClient.class);
        private static final String TTS_PATH = "/api/v1/services/aigc/multimodal-generation/generation";

        private final RestTemplate restTemplate;
        private final String apiUrl;
        private final String apiKey;
        private final String model;
        private final String voice;

        public Qwen3TtsFlashClient(SpeechProperties props) {
            this.apiKey = props.getApiKey();
            this.model = props.getTts().getModel();
            this.voice = props.getTts().getVoice();
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
                URI uri = URI.create(baseUrl);
                return uri.getScheme() + "://" + uri.getHost() + TTS_PATH;
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

                ResponseEntity<Object> response = restTemplate.postForEntity(apiUrl, entity, Object.class);
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

        private byte[] extractAudioBytes(Object responseBody) throws IOException {
            if (!(responseBody instanceof Map<?, ?> root)) {
                throw new IOException("empty TTS response body");
            }

            Object outputObj = root.get("output");
            if (outputObj instanceof Map<?, ?> output) {
                Object audioObj = output.get("audio");
                if (audioObj instanceof Map<?, ?> audio) {
                    Object data = audio.get("data");
                    if (data instanceof String base64 && !base64.isEmpty()) {
                        log.info("TTS audio from base64 data: {} chars", base64.length());
                        return Base64.getDecoder().decode(base64);
                    }
                    Object url = audio.get("url");
                    if (url instanceof String urlStr && !urlStr.isEmpty()) {
                        log.info("TTS audio downloading from OSS: {}", urlStr);
                        try (InputStream is = URI.create(urlStr).toURL().openStream()) {
                            return is.readAllBytes();
                        }
                    }
                }
            }

            throw new IOException("unexpected TTS response format: " + responseBody);
        }
    }

    public static class VoiceCatalog {

        private final Map<String, VoiceProfile> profiles = new LinkedHashMap<>();

        public VoiceCatalog() {
            register(new VoiceProfile("Cherry", "Cherry / 芊悦", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "bright, positive, friendly", "阳光积极、亲切自然小姐姐",
                    List.of("happy", "casual", "cheerful", "neutral"), List.of("zh-CN", "en-US")));
            register(new VoiceProfile("Serena", "Serena / 苏瑶", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "gentle, soft, warm", "温柔小姐姐",
                    List.of("sad", "calm", "comforting", "casual"), List.of("zh-CN", "en-US")));
            register(new VoiceProfile("Ethan", "Ethan / 晨煦", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "warm, sunny, standard Mandarin", "标准普通话，阳光温暖",
                    List.of("happy", "formal", "casual", "storytelling"), List.of("zh-CN", "en-US")));
            register(new VoiceProfile("Chelsie", "Chelsie / 千雪", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "anime-style, virtual companion", "二次元虚拟女友", List.of("happy", "casual"), List.of("zh-CN")));
            register(new VoiceProfile("Momo", "Momo / 茉兔", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "playful, cute, coquettish", "撒娇搞怪，逗你开心",
                    List.of("happy", "excited", "casual"), List.of("zh-CN")));
            register(new VoiceProfile("Vivian", "Vivian / 十三", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "sassy, cute, slightly grumpy", "拽拽的、可爱的小暴躁",
                    List.of("excited", "casual"), List.of("zh-CN")));
            register(new VoiceProfile("Moon", "Moon / 月白", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "cool, handsome, frank", "率性帅气", List.of("casual", "storytelling"), List.of("zh-CN")));
            register(new VoiceProfile("Maia", "Maia / 四月", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "intellectual, gentle", "知性与温柔的碰撞",
                    List.of("formal", "calm", "storytelling"), List.of("zh-CN")));
            register(new VoiceProfile("Kai", "Kai / 凯", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "soothing, relaxing", "耳朵的一场SPA", List.of("calm", "comforting", "sad"), List.of("zh-CN")));
            register(new VoiceProfile("Nofish", "Nofish / 不吃鱼", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "friendly, casual, designer vibe", "不会翘舌音的设计师", List.of("casual", "happy"), List.of("zh-CN")));
            register(new VoiceProfile("Bella", "Bella / 萌宝", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "loli, cute, lively", "喝酒不打醉拳的小萝莉", List.of("happy", "excited", "casual"), List.of("zh-CN")));
            register(new VoiceProfile("Jennifer", "Jennifer / 詹妮弗", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "premium, cinematic, American English", "品牌级、电影质感美语女声",
                    List.of("formal", "storytelling"), List.of("en-US")));
            register(new VoiceProfile("Ryan", "Ryan / 甜茶", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "rhythmic, dramatic, immersive", "节奏拉满，戏感炸裂",
                    List.of("excited", "storytelling", "happy"), List.of("zh-CN")));
            register(new VoiceProfile("Katerina", "Katerina / 卡捷琳娜", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "regal, elegant, rhythmic", "御姐音色，韵律回味十足",
                    List.of("formal", "storytelling", "calm"), List.of("zh-CN")));
            register(new VoiceProfile("Aiden", "Aiden / 艾登", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "friendly, chef-like, American English", "精通厨艺的美语大男孩", List.of("casual", "happy"), List.of("en-US")));
            register(new VoiceProfile("Eldric Sage", "Eldric Sage / 沧明子", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "calm, wise, elderly", "沉稳睿智的老者", List.of("calm", "storytelling", "formal"), List.of("zh-CN")));
            register(new VoiceProfile("Mia", "Mia / 乖小妹", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "gentle, obedient, soft", "温顺如春水，乖巧如初雪", List.of("sad", "calm", "comforting"), List.of("zh-CN")));
            register(new VoiceProfile("Mochi", "Mochi / 沙小弥", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "smart, precocious kid", "聪明伶俐的小大人", List.of("happy", "casual", "storytelling"), List.of("zh-CN")));
            register(new VoiceProfile("Bellona", "Bellona / 燕铮莺", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "loud, vibrant, vivid", "声音洪亮，人物鲜活", List.of("excited", "storytelling"), List.of("zh-CN")));
            register(new VoiceProfile("Vincent", "Vincent / 田叔", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "raspy, smoky, unique", "独特沙哑烟嗓", List.of("calm", "storytelling", "sad"), List.of("zh-CN")));
            register(new VoiceProfile("Bunny", "Bunny / 萌小姬", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "cute, moe, little girl", "萌属性爆棚的小萝莉", List.of("happy", "excited", "casual"), List.of("zh-CN")));
            register(new VoiceProfile("Neil", "Neil / 阿闻", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "professional, news anchor", "专业新闻主持人", List.of("formal", "calm"), List.of("zh-CN")));
            register(new VoiceProfile("Elias", "Elias / 墨讲师", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "rigorous, teaching style", "严谨教学风格", List.of("formal", "calm"), List.of("zh-CN")));
            register(new VoiceProfile("Arthur", "Arthur / 徐大爷", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "rustic, time-worn, sincere", "被岁月浸泡的质朴嗓音",
                    List.of("calm", "storytelling", "sad"), List.of("zh-CN")));
            register(new VoiceProfile("Nini", "Nini / 邻家妹妹", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "soft, sticky, sweet", "糯米糍一样又软又黏",
                    List.of("sad", "comforting", "casual", "calm"), List.of("zh-CN")));
            register(new VoiceProfile("Seren", "Seren / 小婉", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "gentle, soothing, sleep-aid", "温和舒缓，助眠声线", List.of("sad", "calm", "comforting"), List.of("zh-CN")));
            register(new VoiceProfile("Pip", "Pip / 顽屁小孩", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                    "naughty, childlike, playful", "调皮捣蛋充满童真",
                    List.of("happy", "excited", "storytelling"), List.of("zh-CN")));
            register(new VoiceProfile("Stella", "Stella / 少女阿月", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                    "sweet, dreamy, young girl", "甜美迷糊少女音", List.of("happy", "casual", "sad"), List.of("zh-CN")));

            register(new VoiceProfile("Dylan", "Dylan / 北京-晓东", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Beijing dialect, local youth", "胡同里长大的少年",
                    List.of("casual", "happy", "storytelling"), List.of("zh-CN", "beijing")));
            register(new VoiceProfile("Jada", "Jada / 上海-阿珍", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                    "Shanghai/Wu dialect, spirited", "风风火火的沪上阿姐",
                    List.of("casual", "happy", "excited"), List.of("zh-CN", "shanghai")));
            register(new VoiceProfile("Li", "Li / 南京-老李", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Nanjing dialect, patient teacher", "耐心的瑜伽老师",
                    List.of("calm", "formal", "storytelling"), List.of("zh-CN", "nanjing")));
            register(new VoiceProfile("Sunny", "Sunny / 四川-晴儿", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                    "Sichuan dialect, sweet", "甜到心里的川妹子", List.of("happy", "casual", "comforting"), List.of("zh-CN", "sichuan")));
            register(new VoiceProfile("Eric", "Eric / 四川-程川", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Sichuan dialect, lively", "跳脱市井的成都男子",
                    List.of("excited", "casual", "storytelling"), List.of("zh-CN", "sichuan")));
            register(new VoiceProfile("Marcus", "Marcus / 陕西-秦川", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Shaanxi dialect, straightforward", "面宽话短，老陕的味道",
                    List.of("casual", "storytelling", "calm"), List.of("zh-CN", "shaanxi")));
            register(new VoiceProfile("Roy", "Roy / 闽南-阿杰", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Minnan/Taiwanese, humorous", "诙谐直爽的台湾哥仔",
                    List.of("happy", "casual", "storytelling"), List.of("zh-CN", "minnan")));
            register(new VoiceProfile("Peter", "Peter / 天津-李彼得", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Tianjin dialect, comedic", "天津相声，专业捧哏",
                    List.of("happy", "excited", "casual"), List.of("zh-CN", "tianjin")));
            register(new VoiceProfile("Rocky", "Rocky / 粤语-阿强", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                    "Cantonese, humorous", "幽默风趣粤语男，在线陪聊",
                    List.of("happy", "casual", "storytelling"), List.of("zh-CN", "cantonese")));
            register(new VoiceProfile("Kiki", "Kiki / 粤语-阿清", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                    "Cantonese, sweet Hong Kong girl", "甜美的港妹闺蜜",
                    List.of("happy", "casual", "comforting"), List.of("zh-CN", "cantonese")));

            register(new VoiceProfile("Bodega", "Bodega / 博德加", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "passionate, Spanish uncle", "热情的西班牙大叔", List.of("excited", "happy", "storytelling"), List.of("es")));
            register(new VoiceProfile("Sonrisa", "Sonrisa / 索尼莎", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                    "warm, cheerful, Latin American", "热情开朗的拉美大姐", List.of("happy", "excited", "casual"), List.of("es")));
            register(new VoiceProfile("Alek", "Alek / 阿列克", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "cool, warm, Russian", "战斗民族的冷与暖", List.of("calm", "formal", "storytelling"), List.of("ru")));
            register(new VoiceProfile("Dolce", "Dolce / 多尔切", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "lazy, relaxed, Italian", "慵懒的意大利大叔", List.of("calm", "casual", "storytelling"), List.of("it")));
            register(new VoiceProfile("Sohee", "Sohee / 素熙", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                    "gentle, cheerful, Korean", "温柔开朗的韩国欧尼", List.of("happy", "casual", "comforting"), List.of("ko")));
            register(new VoiceProfile("Ono Anna", "Ono Anna / 小野杏", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                    "playful, mischievous, Japanese", "鬼灵精怪的青梅竹马", List.of("happy", "excited", "casual"), List.of("ja")));
            register(new VoiceProfile("Lenn", "Lenn / 莱恩", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "cool, post-punk, German", "西装也听后朋克的德国青年", List.of("calm", "casual", "formal"), List.of("de")));
            register(new VoiceProfile("Emilien", "Emilien / 埃米尔安", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "romantic, French", "浪漫的法国大哥哥", List.of("calm", "storytelling", "comforting"), List.of("fr")));
            register(new VoiceProfile("Andre", "Andre / 安德雷", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "magnetic, deep, steady", "声音磁性，沉稳男生", List.of("calm", "formal", "storytelling"), List.of("zh-CN", "en-US")));
            register(new VoiceProfile("Radio Gol", "Radio Gol / 拉迪奥·戈尔", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                    "football commentator, poetic", "足球诗人解说", List.of("excited", "storytelling", "happy"), List.of("es")));
        }

        private void register(VoiceProfile profile) {
            profiles.put(profile.voiceId(), profile);
        }

        public VoiceProfile get(String voiceId) {
            return profiles.get(voiceId);
        }

        public Optional<VoiceProfile> findByMood(String mood) {
            if (mood == null || mood.isBlank()) {
                return Optional.empty();
            }
            String moodLower = mood.toLowerCase().trim();

            VoiceProfile best = null;
            for (VoiceProfile profile : profiles.values()) {
                for (String suitableMood : profile.suitableMoods()) {
                    if (moodLower.equals(suitableMood)) {
                        return Optional.of(profile);
                    }
                    if (moodLower.contains(suitableMood) && best == null) {
                        best = profile;
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        public VoiceProfile defaultVoice() {
            return profiles.get("Cherry");
        }

        public String generateVoicePrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n--- Available TTS Voices (Qwen3-TTS-Flash) ---\n");
            sb.append("Default: Cherry (bright, friendly female — good for most situations)\n\n");

            sb.append("Standard female voices:\n");
            for (VoiceProfile v : byCategory(VoiceCategory.STANDARD_BILINGUAL)) {
                if (v.gender() == VoiceGender.FEMALE && !v.voiceId().equals("Cherry")) {
                    sb.append("  ").append(voiceLine(v)).append("\n");
                }
            }

            sb.append("\nStandard male voices:\n");
            for (VoiceProfile v : byCategory(VoiceCategory.STANDARD_BILINGUAL)) {
                if (v.gender() == VoiceGender.MALE) {
                    sb.append("  ").append(voiceLine(v)).append("\n");
                }
            }

            sb.append("\nChinese dialect voices:\n");
            for (VoiceProfile v : byCategory(VoiceCategory.CHINESE_DIALECT)) {
                sb.append("  ").append(voiceLine(v)).append("\n");
            }

            sb.append("\nForeign language character voices:\n");
            for (VoiceProfile v : byCategory(VoiceCategory.FOREIGN_CHARACTER)) {
                sb.append("  ").append(voiceLine(v)).append("\n");
            }

            sb.append("\n").append(generateMoodMappingPrompt());

            return sb.toString();
        }

        public String generateMoodMappingPrompt() {
            return """
                    When selecting a voice for SPEECH_GEN:
                    - User sounds sad/upset → pick a warm, gentle voice (Serena, Mia, Seren, Nini, Kai)
                    - User sounds happy/excited → pick a bright, energetic voice (Cherry, Momo, Ethan, Ryan, Bunny)
                    - User wants storytelling → pick a rich, narrative voice (Eldric Sage, Ethan, Vincent, Arthur, Katerina)
                    - User sounds calm/neutral → use default or whatever fits content
                    - User requests Cantonese/粤语/广东话 → use Rocky (humorous male) or Kiki (sweet female)
                    - User requests other Chinese dialects → pick from dialect category (Dylan/Beijing, Jada/Shanghai, Sunny/Sichuan, etc.)
                    - User requests foreign language → pick from foreign character category
                    - User requests a specific voice name → use that exact voice ID
                    - User requests a gender → filter by gender
                    - User requests "read aloud" or "读一下" → consider the content mood when selecting

                    Set the voice via "voice" parameter on SPEECH_GEN tasks.
                    """;
        }

        private String voiceLine(VoiceProfile v) {
            return String.format("%s: %s, %s (%s)", v.voiceId(), v.tone(), v.gender().name().toLowerCase(), v.displayName());
        }

        private List<VoiceProfile> byCategory(VoiceCategory category) {
            List<VoiceProfile> result = new ArrayList<>();
            for (VoiceProfile v : profiles.values()) {
                if (v.category() == category) {
                    result.add(v);
                }
            }
            return result;
        }

        public Map<String, VoiceProfile> all() {
            return Map.copyOf(profiles);
        }
    }

    public enum VoiceCategory {
        STANDARD_BILINGUAL,
        FOREIGN_CHARACTER,
        CHINESE_DIALECT
    }

    public enum VoiceGender {
        MALE,
        FEMALE,
        NEUTRAL
    }

    public record VoiceProfile(
            String voiceId,
            String displayName,
            VoiceGender gender,
            VoiceCategory category,
            String tone,
            String description,
            List<String> suitableMoods,
            List<String> languages
    ) {
    }
}
