package com.youkeda.project.wechatproject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ilink.auto-login=false",
        "agent.tools.automation.heartbeat-enabled=false"
})
class ApplicationContextTests {

    @Test
    void contextLoads() {
    }

}
