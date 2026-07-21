package com.youkeda.project.wechatproject.agent.speech;

import java.util.List;

public record VoiceProfile(
        String voiceId,
        String displayName,
        VoiceGender gender,
        VoiceCategory category,
        String tone,
        String description,
        List<String> suitableMoods,
        List<String> languages
) {}
