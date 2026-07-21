package com.youkeda.project.wechatproject.agent.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音识别 & 语音生成 配置属性。
 */
@ConfigurationProperties(prefix = "agent.speech")
public class SpeechProperties {

    /** API Key（STT和TTS共用） */
    private String apiKey;

    /** API 基础URL（OpenAI兼容，STT和TTS共用） */
    private String apiUrl;

    /** 是否启用语音功能 */
    private boolean enabled = true;

    /** STT配置 */
    private Stt stt = new Stt();

    /** TTS配置 */
    private Tts tts = new Tts();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Stt getStt() {
        return stt;
    }

    public void setStt(Stt stt) {
        this.stt = stt;
    }

    public Tts getTts() {
        return tts;
    }

    public void setTts(Tts tts) {
        this.tts = tts;
    }

    public static class Stt {
        private boolean enabled = true;
        private String model = "qwen3-asr-flash";
        private String apiUrl;
        private String ffmpegPath = "ffmpeg";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getFfmpegPath() {
            return ffmpegPath;
        }

        public void setFfmpegPath(String ffmpegPath) {
            this.ffmpegPath = ffmpegPath;
        }
    }

    public static class Tts {
        private boolean enabled = true;
        private String model = "qwen-audio-3.0-tts-flash";
        private String voice = "longanhuan_v3.6";
        private String format = "wav";
        private int sampleRate = 24000;
        private String apiUrl;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }
    }
}
