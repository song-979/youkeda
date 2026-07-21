package com.youkeda.project.wechatproject.agent.speech;

import java.io.IOException;

public interface TextToSpeechClient {

    TtsResult synthesize(String text) throws IOException;

    default TtsResult synthesize(String text, String voice) throws IOException {
        return synthesize(text);
    }

    default TtsResult synthesize(String text, String voice, String instruction) throws IOException {
        return synthesize(text, voice);
    }
}
