package com.youkeda.project.wechatproject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ilink.auto-login=false",
        "agent.speech.enabled=false"
})
class ApplicationNoSpeechTests {

    @Test
    void contextLoadsWithoutSpeech() {
    }
}
