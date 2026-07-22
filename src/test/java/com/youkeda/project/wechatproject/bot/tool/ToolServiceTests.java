package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.SystemTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false"
})
class ToolServiceTests {

    private static final Path TOOL_SOURCE_ROOT = Path.of(
            "src/main/java/com/youkeda/project/wechatproject/bot/tool");

    @Autowired
    private ToolRuntime toolRuntime;

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresProjectToolsWithoutRequiringOuterLoopChanges() {
        assertThat(toolRuntime.tools()).hasAtLeastOneElementOfType(SystemTools.class);
        assertThat(toolRuntime.asSpringAiTools()).isNotEmpty();
        assertThat(context.getBeansOfType(ToolChatClientFactory.class)).hasSize(1);
    }

    @Test
    void toolLayerDoesNotDependOnOuterAgentLoop() throws IOException {
        try (var stream = Files.walk(TOOL_SOURCE_ROOT)) {
            for (Path sourceFile : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
                assertThat(source)
                        .as("tool source must not reference outer loop types: %s", sourceFile)
                        .doesNotContain("bot.service.OrchestrationService")
                        .doesNotContain("OrchestrationService.")
                        .doesNotContain("MessageRouter")
                        .doesNotContain("AgentTask")
                        .doesNotContain("AgentUnit")
                        .doesNotContain("TaskScratchpad");
            }
        }
    }
}
