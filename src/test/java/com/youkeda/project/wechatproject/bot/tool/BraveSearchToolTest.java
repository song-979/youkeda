package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

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
    void returnsErrorMessageOnApiError() {
        BraveSearchTool tool = new BraveSearchTool();
        tool.setApiKey("invalid-key");
        tool.setApiUrl("https://uapis.cn/api/v1/search/aggregate");
        String result = tool.webSearch("hello world");
        // should return an error message (either auth error or network error)
        assertThat(result).isNotEmpty();
        assertThat(result).contains("搜索");
    }
}
