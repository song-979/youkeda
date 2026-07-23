package com.youkeda.project.wechatproject.bot.tool;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.bot.tool.ToolService.SystemTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentTask;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ChatAgent;
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
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("tool-loop-response");

        ChatAgent chatAgent = new ChatAgent(legacyClient, agentProperties(), testFactory(toolChatClient));

        AgentResult result = chatAgent.execute(new AgentTask("CHAT", "现在几点", Map.of()));

        assertThat(result.output()).isEqualTo("tool-loop-response");
        verify(legacyClient, never()).chatStream("现在几点", List.of(), List.of());
    }

    @Test
    void chatAgentKeepsLegacyClientForImageTasks() throws IOException {
        AiModelClient legacyClient = mock(AiModelClient.class);
        ChatClient toolChatClient = mock(ChatClient.class);
        List<String> imageUrls = List.of("data:image/png;base64,abc");

        when(legacyClient.chatStream("看图", imageUrls, List.of())).thenReturn("legacy-response");

        ChatAgent chatAgent = new ChatAgent(legacyClient, agentProperties(), testFactory(toolChatClient));

        AgentResult result = chatAgent.execute(new AgentTask("CHAT", "看图", Map.of("imageUrls", imageUrls)));

        assertThat(result.output()).isEqualTo("legacy-response");
        verify(toolChatClient, never()).prompt();
    }

    @Test
    void chatAgentAdvertisesGenericToolAbilityOnly() {
        ChatAgent chatAgent = new ChatAgent(mock(AiModelClient.class));

        assertThat(chatAgent.getCapability().strengths()).contains("runtime-tools");
        assertThat(chatAgent.getCapability().description()).contains("internal tool loop");
        assertThat(chatAgent.getCapability().description()).doesNotContain("get_current_datetime");
    }

    @Test
    void automationRuntimeCreatesAndCancelsFutureReminder() {
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        AutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = new AutomationRuntime(
                store,
                scheduler,
                (recipientId, message) -> {
                },
                automationPropertiesWithDefaultRecipient(),
                Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneId.of("Asia/Shanghai")));

        AutomationRuntime.ReminderResult created = runtime.createReminder(
                "吃药",
                "2026-07-22T20:00:00+08:00",
                "该吃药了");

        assertThat(created.success()).isTrue();
        assertThat(store.listReminders(AutomationStore.ReminderStatus.PENDING)).hasSize(1);
        assertThat(scheduler.scheduledIds).containsExactly(created.reminder().id());

        AutomationRuntime.ReminderResult cancelled = runtime.cancelReminder(created.reminder().id());

        assertThat(cancelled.success()).isTrue();
        assertThat(store.findReminder(created.reminder().id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.CANCELLED);
        assertThat(scheduler.cancelledIds).containsExactly(created.reminder().id());
    }

    @Test
    void automationRuntimeRejectsPastReminderTime() {
        AutomationRuntime runtime = new AutomationRuntime(
                new InMemoryAutomationStore(),
                new RecordingReminderScheduler(),
                (recipientId, message) -> {
                },
                new AutomationProperties(),
                Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneId.of("Asia/Shanghai")));

        AutomationRuntime.ReminderResult result = runtime.createReminder(
                "过期提醒",
                "2026-07-22T11:59:00+08:00",
                "已经过期");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("future");
    }

    @Test
    void jsonAutomationStorePersistsRemindersAndScheduleItems() {
        JsonAutomationStore store = new JsonAutomationStore(tempDir);
        AutomationStore.Reminder reminder = new AutomationStore.Reminder(
                "R-STORE01",
                "call customer",
                Instant.parse("2026-07-23T01:00:00Z"),
                "call customer now",
                AutomationStore.ReminderStatus.PENDING,
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-22T04:00:00Z"),
                null,
                0);
        AutomationStore.ScheduleItem scheduleItem = new AutomationStore.ScheduleItem(
                "S-STORE01",
                "project meeting",
                Instant.parse("2026-07-23T07:00:00Z"),
                Instant.parse("2026-07-23T08:00:00Z"),
                "weekly sync",
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-22T04:00:00Z"));

        store.saveReminder(reminder);
        store.saveScheduleItem(scheduleItem);
        store.saveRecipientBinding(new AutomationStore.RecipientBinding(
                "bound-user@im.wechat",
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-22T04:00:00Z")));

        JsonAutomationStore reloaded = new JsonAutomationStore(tempDir);

        assertThat(reloaded.findReminder("R-STORE01")).contains(reminder);
        assertThat(reloaded.listScheduleItems(
                Instant.parse("2026-07-23T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"))).containsExactly(scheduleItem);
        assertThat(reloaded.getRecipientBinding())
                .map(AutomationStore.RecipientBinding::recipientId)
                .contains("bound-user@im.wechat");
    }

    @Test
    void recipientBindingListenerBindsFirstSenderAndDoesNotOverwrite() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecipientBindingListener listener = new RecipientBindingListener(store, Clock.fixed(
                Instant.parse("2026-07-22T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        WeixinMessage first = mock(WeixinMessage.class);
        WeixinMessage second = mock(WeixinMessage.class);
        when(first.getFrom_user_id()).thenReturn("user-one@im.wechat");
        when(second.getFrom_user_id()).thenReturn("user-two@im.wechat");

        listener.onMessages(List.of(first));
        listener.onMessages(List.of(second));

        assertThat(store.getRecipientBinding())
                .map(AutomationStore.RecipientBinding::recipientId)
                .contains("user-one@im.wechat");
    }

    @Test
    void automationRuntimeUsesBoundRecipientWhenDefaultRecipientIsNotConfigured() {
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        store.saveRecipientBinding(new AutomationStore.RecipientBinding(
                "bound-user@im.wechat",
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-22T04:00:00Z")));
        AutomationRuntime runtime = new AutomationRuntime(
                store,
                new RecordingReminderScheduler(),
                dispatcher,
                new AutomationProperties(),
                Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneId.of("Asia/Shanghai")));

        AutomationRuntime.ReminderResult created = runtime.createReminder(
                "take medicine",
                "2026-07-22T12:01:00Z",
                "take medicine now");
        runtime.triggerReminder(created.reminder().id());

        assertThat(dispatcher.recipientIds).containsExactly("bound-user@im.wechat");
        assertThat(store.findReminder(created.reminder().id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.SENT);
    }

    private static class RecordingReminderScheduler implements AutomationRuntime.ReminderScheduler {
        private final List<String> scheduledIds = new ArrayList<>();
        private final List<String> cancelledIds = new ArrayList<>();

        @Override
        public void schedule(AutomationStore.Reminder reminder, Runnable task) {
            scheduledIds.add(reminder.id());
        }

        @Override
        public boolean cancel(String reminderId) {
            cancelledIds.add(reminderId);
            return true;
        }
    }

    private static class RecordingReminderDispatcher implements AutomationRuntime.ReminderDispatcher {
        private final List<String> recipientIds = new ArrayList<>();

        @Override
        public void send(String recipientId, String message) {
            recipientIds.add(recipientId);
        }
    }

    private static class InMemoryAutomationStore implements AutomationStore {
        private final List<Reminder> reminders = new ArrayList<>();
        private final List<ScheduleItem> scheduleItems = new ArrayList<>();
        private RecipientBinding recipientBinding;

        @Override
        public synchronized Reminder saveReminder(Reminder reminder) {
            reminders.removeIf(existing -> existing.id().equals(reminder.id()));
            reminders.add(reminder);
            return reminder;
        }

        @Override
        public synchronized Optional<Reminder> findReminder(String id) {
            return reminders.stream().filter(reminder -> reminder.id().equals(id)).findFirst();
        }

        @Override
        public synchronized List<Reminder> listReminders(ReminderStatus status) {
            if (status == null) {
                return List.copyOf(reminders);
            }
            return reminders.stream().filter(reminder -> reminder.status() == status).toList();
        }

        @Override
        public synchronized ScheduleItem saveScheduleItem(ScheduleItem item) {
            scheduleItems.removeIf(existing -> existing.id().equals(item.id()));
            scheduleItems.add(item);
            return item;
        }

        @Override
        public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive) {
            return scheduleItems.stream()
                    .filter(item -> !item.startAt().isBefore(fromInclusive) && item.startAt().isBefore(toExclusive))
                    .toList();
        }

        @Override
        public synchronized Optional<RecipientBinding> getRecipientBinding() {
            return Optional.ofNullable(recipientBinding);
        }

        @Override
        public synchronized RecipientBinding saveRecipientBinding(RecipientBinding binding) {
            recipientBinding = binding;
            return binding;
        }
    }

    private static AgentProperties agentProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setSystemPrompt("test system prompt");
        return properties;
    }

    private static AutomationProperties automationPropertiesWithDefaultRecipient() {
        AutomationProperties properties = new AutomationProperties();
        properties.setDefaultRecipientId("default-user@im.wechat");
        return properties;
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
