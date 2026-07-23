package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentTask;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ChatAgent;
import com.youkeda.project.wechatproject.bot.tool.ToolService.SystemTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @TempDir
    private Path tempDir;

    @Test
    void wiresProjectToolsWithoutRequiringOuterLoopChanges() {
        assertThat(toolRuntime.tools()).hasAtLeastOneElementOfType(SystemTools.class);
        assertThat(toolRuntime.tools()).hasAtLeastOneElementOfType(WeatherTools.class);
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

    @Test
    void chatAgentUsesToolLoopForTextTasks() throws IOException {
        AiModelClient legacyClient = mock(AiModelClient.class);
        ChatClient toolChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(toolChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("tool-loop-response");

        ChatAgent chatAgent = new ChatAgent(legacyClient, null, testFactory(toolChatClient));

        AgentResult result = chatAgent.execute(new AgentTask("CHAT", "现在几点", Map.of()));

        assertThat(result.output()).isEqualTo("tool-loop-response");
        verify(legacyClient, never()).chatStream("现在几点", List.of(), List.of());
    }

    @Test
    void chatAgentUsesToolLoopWithImagesWhenAvailable() throws IOException {
        AiModelClient legacyClient = mock(AiModelClient.class);
        ChatClient toolChatClient = mock(ChatClient.class);
        List<String> imageUrls = List.of("data:image/png;base64,abc");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(toolChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("tool-loop-response");

        ChatAgent chatAgent = new ChatAgent(legacyClient, null, testFactory(toolChatClient));

        AgentResult result = chatAgent.execute(new AgentTask("CHAT", "看图", Map.of("imageUrls", imageUrls)));

        assertThat(result.output()).isEqualTo("tool-loop-response");
        verify(toolChatClient).prompt();
        verify(legacyClient, never()).chatStream(anyString(), anyList(), anyList());
    }

    @Test
    void chatAgentAdvertisesGenericToolAbilityOnly() {
        ChatAgent chatAgent = new ChatAgent(mock(AiModelClient.class));

        assertThat(chatAgent.getCapability().strengths()).contains("runtime-tools");
        assertThat(chatAgent.getCapability().description()).contains("tool-assisted runtime tasks");
        assertThat(chatAgent.getCapability().description()).doesNotContain("get_current_datetime");
    }

    private static ToolChatClientFactory testFactory(ChatClient chatClient) {
        return new ToolChatClientFactory(null, null) {
            @Override
            public ChatClient create() {
                return chatClient;
            }
        };
    }
}
