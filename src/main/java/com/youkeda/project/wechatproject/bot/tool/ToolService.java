package com.youkeda.project.wechatproject.bot.tool;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.time.Clock;
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
        AutomationProperties.class,
        WeatherTools.WeatherProperties.class
})
@ConditionalOnProperty(prefix = "agent.tools", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolService {

    @Bean
    @ConditionalOnMissingBean
    public ToolRuntime toolRuntime(List<ProjectTool> projectTools,
                                   ObjectProvider<RecipientBindingListener> recipientBindingListenerProvider) {
        recipientBindingListenerProvider.getIfAvailable();
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
    @ConditionalOnMissingBean(name = "automationTaskScheduler")
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TaskScheduler automationTaskScheduler(AutomationProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("tool-automation-");
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutomationStore automationStore(AutomationProperties properties) {
        return new JsonAutomationStore(Path.of(properties.getStoragePath()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutomationRuntime.ReminderScheduler reminderScheduler(TaskScheduler automationTaskScheduler) {
        return new AutomationRuntime.SpringReminderScheduler(automationTaskScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutomationRuntime automationRuntime(AutomationStore automationStore,
                                               AutomationRuntime.ReminderScheduler reminderScheduler,
                                               AutomationProperties properties,
                                               ObjectProvider<ILinkClient> ilinkClientProvider) {
        return new AutomationRuntime(
                automationStore,
                reminderScheduler,
                (recipientId, message) -> {
                    ILinkClient client = ilinkClientProvider.getIfAvailable();
                    if (client == null) {
                        throw new IllegalStateException("iLink client is not available");
                    }
                    client.sendText(recipientId, message);
                },
                properties,
                Clock.systemDefaultZone());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutomationTools automationTools(AutomationRuntime automationRuntime, AutomationProperties properties) {
        return new AutomationTools(automationRuntime, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.automation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RecipientBindingListener recipientBindingListener(AutomationStore automationStore,
                                                             ObjectProvider<MessageBridge> messageBridgeProvider) {
        return new RecipientBindingListener(automationStore, Clock.systemDefaultZone(), messageBridgeProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.weather", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WeatherTools weatherTools(WeatherTools.WeatherProperties weatherProperties) {
        return new WeatherTools(weatherProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.placeid", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AmapPlaceIdTools amapPlaceIdTools(@Value("${agent.tools.weather.amap-private-key:}") String amapPrivateKey) {
        return new AmapPlaceIdTools(amapPrivateKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.aroundsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AmapAroundSearchTools amapAroundSearchTools(@Value("${agent.tools.weather.amap-private-key:}") String amapPrivateKey) {
        return new AmapAroundSearchTools(amapPrivateKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.direction", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AmapDirectionTools amapDirectionTools(@Value("${agent.tools.weather.amap-private-key:}") String amapPrivateKey) {
        return new AmapDirectionTools(amapPrivateKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.staticmap", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AmapStaticMapTools amapStaticMapTools(@Value("${agent.tools.weather.amap-private-key:}") String amapPrivateKey) {
        return new AmapStaticMapTools(amapPrivateKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.didi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DiDiMcpClient diDiMcpClient(@Value("${agent.tools.didi.api-key:}") String apiKey) {
        return new DiDiMcpClient("https://mcp.didichuxing.com/mcp-servers-sandbox?key=" + apiKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.tools.didi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DiDiTaxiTools diDiTaxiTools(DiDiMcpClient diDiMcpClient) {
        return new DiDiTaxiTools(diDiMcpClient);
    }

    public interface ProjectTool {
        /** 工具能力类别，用于编排模型路由决策。例如 "information", "web_content", "media_generation" */
        default String category() { return ""; }
    }

    public static class ToolRuntime {

        private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

        private static final Map<String, String> CATEGORY_LABELS = Map.of(
                "information", "信息查询（时间、天气、搜索）",
                "web_content", "网页内容获取",
                "media_generation", "媒体生成（GIF表情）",
                "automation", "定时提醒与日程（创建提醒、定时任务、日常安排、闹钟）",
                "local_files", "本地文件检索、读取和发送",
                "map_navigation", "高德地图（地点搜索、周边搜索、路线规划、静态地图）",
                "didi_taxi", "滴滴打车（价格预估、叫车、订单查询、取消订单、司机位置、行程链接）"
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
        public String category() {
            return "information";
        }

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
