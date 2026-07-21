package com.youkeda.project.wechatproject.agent.orchestration;

import com.youkeda.project.wechatproject.agent.speech.TextToSpeechClient;
import com.youkeda.project.wechatproject.agent.speech.TtsResult;
import com.youkeda.project.wechatproject.agent.speech.VoiceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class SpeechAgent implements AgentUnit {

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

        return AgentResult.success(task.taskId(), output,
                "[speech generated] " + textToSpeak);
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
