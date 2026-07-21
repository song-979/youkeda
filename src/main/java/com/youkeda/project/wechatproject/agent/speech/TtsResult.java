package com.youkeda.project.wechatproject.agent.speech;

public record TtsResult(
        byte[] audioBytes,
        String format,
        int sampleRate,
        int durationMs) {
}
