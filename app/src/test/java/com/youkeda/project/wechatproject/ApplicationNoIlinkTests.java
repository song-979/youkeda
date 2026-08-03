package com.youkeda.project.wechatproject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false"
})
class ApplicationNoIlinkTests {

    @Test
    void contextLoadsWithoutIlink() {
    }
}
