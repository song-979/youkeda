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
        "agent.tools.webparse.enabled=true",
        "agent.tools.webparse.api-key=test-api-key-for-validation"
})
class WebParseToolTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolRuntime toolRuntime;

    @Test
    void beanIsRegisteredWhenEnabled() {
        WebParseTool bean = context.getBean(WebParseTool.class);
        assertThat(bean).isNotNull();
        assertThat(bean).isInstanceOf(ProjectTool.class);
    }

    @Test
    void toolIsCollectedByToolRuntime() {
        assertThat(toolRuntime.tools())
                .anyMatch(tool -> tool instanceof WebParseTool);
        assertThat(toolRuntime.asSpringAiTools())
                .anyMatch(tool -> tool instanceof WebParseTool);
    }

    @Test
    void metadataMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = WebParseTool.class.getMethod("getWebpageMetadata", String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("get_webpage_metadata");
        assertThat(annotation.description()).contains("元数据");
    }

    @Test
    void extractImagesMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = WebParseTool.class.getMethod("extractWebpageImages", String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("extract_webpage_images");
        assertThat(annotation.description()).contains("图片");
    }

    @Test
    void returnsErrorWhenApiKeyIsBlank() {
        WebParseTool tool = new WebParseTool();
        tool.setApiKey("");
        assertThat(tool.getWebpageMetadata("https://example.com")).contains("未配置");
        assertThat(tool.extractWebpageImages("https://example.com")).contains("未配置");
    }

    @Test
    void returnsErrorWhenUrlIsBlank() {
        WebParseTool tool = new WebParseTool();
        tool.setApiKey("some-key");
        assertThat(tool.getWebpageMetadata("")).contains("URL 为空");
        assertThat(tool.extractWebpageImages("")).contains("URL 为空");
    }

    @Test
    void returnsErrorMessageOnApiError() {
        WebParseTool tool = new WebParseTool();
        tool.setApiKey("invalid-key");
        String result = tool.getWebpageMetadata("https://example.com");
        assertThat(result).isNotEmpty();
        assertThat(result).contains("网页");
    }
}
