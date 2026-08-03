package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false",
        "agent.tools.motou.enabled=true",
        "agent.tools.motou.api-key=test-api-key-for-validation"
})
class MotouToolTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolRuntime toolRuntime;

    @Test
    void beanIsRegisteredWhenEnabled() {
        MotouTool bean = context.getBean(MotouTool.class);
        assertThat(bean).isNotNull();
        assertThat(bean).isInstanceOf(ProjectTool.class);
    }

    @Test
    void toolIsCollectedByToolRuntime() {
        assertThat(toolRuntime.tools())
                .anyMatch(tool -> tool instanceof MotouTool);
        assertThat(toolRuntime.asSpringAiTools())
                .anyMatch(tool -> tool instanceof MotouTool);
    }

    @Test
    void fromUrlMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = MotouTool.class.getMethod("generateMotouGifFromUrl", String.class, String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("generate_motou_gif_from_url");
        assertThat(annotation.description()).contains("摸摸头");
    }

    @Test
    void fromImageMethodHasToolAnnotation() throws NoSuchMethodException {
        Method method = MotouTool.class.getMethod("generateMotouGifFromImage", String.class, ToolContext.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("generate_motou_gif_from_image");
        assertThat(annotation.description()).contains("摸摸头");
    }

    @Test
    void returnsErrorWhenApiKeyIsBlank() {
        MotouTool tool = new MotouTool();
        tool.setApiKey("");
        assertThat(tool.generateMotouGifFromUrl("https://example.com/avatar.jpg", null)).contains("未配置");
        assertThat(tool.generateMotouGifFromImage(null, emptyContext())).contains("未配置");
    }

    @Test
    void returnsErrorWhenImageUrlIsBlank() {
        MotouTool tool = new MotouTool();
        tool.setApiKey("some-key");
        assertThat(tool.generateMotouGifFromUrl("", null)).contains("URL为空");
    }

    @Test
    void returnsErrorWhenToolContextHasNoImages() {
        MotouTool tool = new MotouTool();
        tool.setApiKey("some-key");
        assertThat(tool.generateMotouGifFromImage(null, emptyContext())).contains("无法获取当前消息的图片数据");
    }

    @Test
    void returnsErrorOnInvalidBase64Format() {
        MotouTool tool = new MotouTool();
        tool.setApiKey("some-key");
        String result = tool.generateMotouGifFromImage(null,
                contextWithImages(List.of("not-a-data-url")));
        assertThat(result).contains("格式错误");
    }

    @Test
    void returnsErrorMessageOnApiError() {
        MotouTool tool = new MotouTool();
        tool.setApiKey("invalid-key");
        String result = tool.generateMotouGifFromUrl("https://example.com/avatar.jpg", null);
        assertThat(result).isNotEmpty();
    }

    private static ToolContext emptyContext() {
        return new ToolContext(Map.of());
    }

    private static ToolContext contextWithImages(List<String> imageUrls) {
        return new ToolContext(Map.of("imageBase64Urls", imageUrls));
    }
}
