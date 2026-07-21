package com.youkeda.project.wechatproject.agent.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语音功能自动装配。
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.speech", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpeechAutoConfiguration.class);

    /**
     * STT Bean — Fun-ASR 语音识别。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech.stt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpeechToTextClient speechToTextClient(SpeechProperties props, AudioConverter audioConverter) {
        log.info("creating FunAsrSttClient: model={}, url={}", props.getStt().getModel(), props.getStt().getApiUrl());
        return new FunAsrSttClient(props, audioConverter);
    }

    /**
     * 音频格式转换器。
     */
    @Bean
    @ConditionalOnMissingBean
    public AudioConverter audioConverter(SpeechProperties props) {
        return new AudioConverter(props.getStt().getFfmpegPath());
    }

    /**
     * 语音音色目录 — 包含所有可用音色及其元数据。
     */
    @Bean
    @ConditionalOnMissingBean
    public VoiceCatalog voiceCatalog() {
        log.info("creating VoiceCatalog");
        return new VoiceCatalog();
    }

    /**
     * TTS Bean — Qwen3-TTS-Flash 语音生成。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech.tts", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TextToSpeechClient textToSpeechClient(SpeechProperties props) {
        log.info("creating Qwen3TtsFlashClient: model={}, voice={}, url={}",
                props.getTts().getModel(), props.getTts().getVoice(), props.getApiUrl());
        return new Qwen3TtsFlashClient(props);
    }
}
