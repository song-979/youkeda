package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.chat.BraveSearchTool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false",
        "agent.tools.search.enabled=true",
        "agent.tools.search.api-key=test-api-key-for-validation"
})
class BraveSearchToolTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolRuntime toolRuntime;

    @Test
    void beanIsRegisteredWhenEnabled() {
        BraveSearchTool bean = context.getBean(BraveSearchTool.class);
        assertThat(bean).isNotNull();
        assertThat(bean).isInstanceOf(ProjectTool.class);
    }

    @Test
    void toolIsCollectedByToolRuntime() {
        assertThat(toolRuntime.tools())
                .anyMatch(tool -> tool instanceof BraveSearchTool);
        assertThat(toolRuntime.asSpringAiTools())
                .anyMatch(tool -> tool instanceof BraveSearchTool);
    }

    @Test
    void webSearchMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = BraveSearchTool.class.getMethod("webSearch", String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("web_search");
        assertThat(annotation.description()).contains("搜索");
    }

    @Test
    void returnsErrorMessageWhenApiKeyIsBlank() {
        BraveSearchTool tool = new BraveSearchTool();
        tool.setApiKey("");
        String result = tool.webSearch("test");
        assertThat(result).contains("未配置");
    }

    @Test
    void returnsErrorMessageWhenQueryIsBlank() {
        BraveSearchTool tool = new BraveSearchTool();
        tool.setApiKey("some-key");
        String result = tool.webSearch("");
        assertThat(result).contains("为空");
    }

    @Test
    void returnsProviderUnavailableMessageOnHttpServerError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://uapis.cn/api/v1/search/aggregate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"服务器内部错误\"}"));

        BraveSearchTool tool = new TestableBraveSearchTool(restTemplate);
        tool.setApiKey("some-key");

        String result = tool.webSearch("site:github.com/confused-ai/personaforge README");

        assertThat(result).contains("搜索服务暂时不可用");
        assertThat(result).contains("HTTP 500");
        server.verify();
    }

    private static class TestableBraveSearchTool extends BraveSearchTool {

        private final RestTemplate restTemplate;

        private TestableBraveSearchTool(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
        }

        @Override
        protected RestTemplate buildRestTemplate() {
            return restTemplate;
        }
    }
}
