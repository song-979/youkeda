package com.youkeda.project.wechatproject;

import com.youkeda.project.wechatproject.bot.handler.MessageHandler;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ImageGenAgent;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.MessageRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ilink.auto-login=false",
        "agent.ai.enabled=false"
})
class ApplicationNoAiTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutAi() {
        assertThat(context.getBeansOfType(ImageGenClient.class)).isEmpty();
        assertThat(context.getBeansOfType(ImageGenAgent.class)).isEmpty();
        assertThat(context.getBeansOfType(MessageRouter.class)).isEmpty();
        assertThat(context.getBeansOfType(MessageHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(DocumentService.class)).isEmpty();
    }
}
