package com.youkeda.project.wechatproject.bot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI tools extension point.
 * <p>
 * Keep the inner tool-calling loop isolated here. The outer multi-agent loop
 * should only consume ToolRuntime or ToolChatClientFactory when it needs tools.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        ToolService.ToolProperties.class,
        WeatherTools.WeatherProperties.class
})
@ConditionalOnProperty(prefix = "agent.tools", name = "enabled", havingValue = "true", matchIfMissing = true)
public class
ToolService {

    @Bean
    @ConditionalOnMissingBean
    public ToolRuntime toolRuntime(List<ProjectTool> projectTools) {
        return new ToolRuntime(projectTools);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolChatClientFactory toolChatClientFactory(ChatModel chatModel, ToolRuntime toolRuntime) {
        return new ToolChatClientFactory(chatModel, toolRuntime);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.system", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SystemTools systemTools() {
        return new SystemTools();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.weather", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WeatherTools weatherTools(WeatherTools.WeatherProperties weatherProperties) {
        return new WeatherTools(weatherProperties);
    }

    public interface ProjectTool {
        /** 工具能力类别，用于编排模型路由决策。如 "information", "web_content", "media_generation" */
        default String category() { return ""; }
    }

    public static class ToolRuntime {

        private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

        private static final Map<String, String> CATEGORY_LABELS = Map.of(
                "information", "信息查询（时间、天气、搜索）",
                "web_content", "网页内容获取",
                "media_generation", "媒体生成（GIF表情）"
        );

        private final List<ProjectTool> tools;

        public ToolRuntime(List<ProjectTool> tools) {
            this.tools = tools != null ? List.copyOf(tools) : List.of();
            log.info("Spring AI tool runtime initialized with {} tool group(s)", this.tools.size());
        }

        public List<ProjectTool> tools() {
            return tools;
        }

        public Object[] asSpringAiTools() {
            return tools.toArray(Object[]::new);
        }

        public boolean isEmpty() {
            return tools.isEmpty();
        }

        /** 汇总所有工具的能力类别，用于编排模型路由。去重后按字母排序。 */
        public String getCategorySummary() {
            return tools.stream()
                    .map(ProjectTool::category)
                    .filter(c -> !c.isEmpty())
                    .distinct()
                    .sorted()
                    .map(c -> c + "(" + CATEGORY_LABELS.getOrDefault(c, c) + ")")
                    .collect(Collectors.joining(", "));
        }
    }

    public static class ToolChatClientFactory {

        private final ChatModel chatModel;
        private final ToolRuntime toolRuntime;

        public ToolChatClientFactory(ChatModel chatModel, ToolRuntime toolRuntime) {
            this.chatModel = chatModel;
            this.toolRuntime = toolRuntime;
        }

        public ChatClient create() {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            Object[] tools = toolRuntime.asSpringAiTools();
            return tools.length == 0 ? builder.build() : builder.defaultTools(tools).build();
        }
    }

    public static class SystemTools implements ProjectTool {

        @Override
        public String category() { return "information"; }

        @Tool(name = "get_current_datetime", description = "Get the current date and time for the application timezone.")
        public String getCurrentDateTime() {
            return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
    }

    @ConfigurationProperties(prefix = "agent.tools")
    public static class ToolProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
