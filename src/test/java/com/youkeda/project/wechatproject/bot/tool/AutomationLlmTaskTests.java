package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationLlmTaskTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-22T04:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @Test
    void llmTaskExecutesSavedInstructionAtTriggerTime() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        RecordingScheduledTaskExecutor executor = new RecordingScheduledTaskExecutor();
        AutomationRuntime runtime = newRuntime(store, dispatcher, executor);

        AutomationRuntime.ReminderResult created = runtime.createLlmTask(
                "weather at nine",
                "2026-07-22T20:00:00+08:00",
                "现在查询杭州市余杭区天气并告诉用户。",
                "今晚八点告诉我余杭天气",
                List.of("information"));

        assertThat(created.success()).isTrue();
        assertThat(created.reminder().taskKind()).isEqualTo(AutomationStore.AutomationTaskKind.LLM_TASK);
        assertThat(executor.requests).isEmpty();

        runtime.triggerReminder(created.reminder().id());

        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.getFirst().instruction()).isEqualTo("现在查询杭州市余杭区天气并告诉用户。");
        assertThat(dispatcher.messages).containsExactly("trigger-time weather result");
        assertThat(store.findReminder(created.reminder().id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.SENT);
    }

    @Test
    void textReminderDoesNotCallScheduledTaskExecutor() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        RecordingScheduledTaskExecutor executor = new RecordingScheduledTaskExecutor();
        AutomationRuntime runtime = newRuntime(store, dispatcher, executor);

        AutomationRuntime.ReminderResult created = runtime.createReminder(
                "drink water",
                "2026-07-22T20:00:00+08:00",
                "drink water now");

        runtime.triggerReminder(created.reminder().id());

        assertThat(executor.requests).isEmpty();
        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.getFirst()).contains("drink water now");
    }

    @Test
    void llmTaskRetriesTwiceAndNotifiesUserAfterFinalFailure() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        FailingScheduledTaskExecutor executor = new FailingScheduledTaskExecutor();
        AutomationRuntime runtime = newRuntime(store, dispatcher, executor);

        AutomationRuntime.ReminderResult created = runtime.createLlmTask(
                "weather at nine",
                "2026-07-22T20:00:00+08:00",
                "现在查询天气。",
                "今晚八点告诉我天气",
                List.of("information"));

        runtime.triggerReminder(created.reminder().id());

        assertThat(executor.attempts).isEqualTo(3);
        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.getFirst()).contains("计划任务执行失败");
        assertThat(dispatcher.messages.getFirst()).contains("weather API unavailable");
        assertThat(store.findReminder(created.reminder().id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.FAILED);
    }

    @Test
    void recurringLlmTaskFailurePausesFutureRuns() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        FailingScheduledTaskExecutor executor = new FailingScheduledTaskExecutor();
        AutomationRuntime runtime = newRuntime(store, dispatcher, executor);

        AutomationRuntime.RecurringTaskResult created = runtime.createRecurringLlmTask(
                "daily weather",
                AutomationStore.RecurringScheduleType.DAILY,
                "09:00",
                "现在查询今天杭州市余杭区天气并告诉用户。",
                "每天九点告诉我余杭天气",
                List.of("information"));
        AutomationStore.Reminder firstInstance = store.listReminders(AutomationStore.ReminderStatus.PENDING).getFirst();

        runtime.triggerReminder(firstInstance.id());

        assertThat(store.findRecurringTask(created.task().id()))
                .map(AutomationStore.RecurringTask::status)
                .contains(AutomationStore.RecurringTaskStatus.PAUSED);
        assertThat(store.listReminders(AutomationStore.ReminderStatus.PENDING)).isEmpty();
    }

    @Test
    void automationPlanUpdateModifiesExistingLlmTaskInsteadOfCreatingAnotherOne() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, new RecordingReminderDispatcher(), new RecordingScheduledTaskExecutor());
        AutomationTools tools = new AutomationTools(runtime, automationProperties());
        String createPlan = """
                {
                  "operation": "CREATE",
                  "kind": "LLM_TASK",
                  "title": "weather at nine",
                  "runAt": "2026-07-22T20:00:00+08:00",
                  "instruction": "现在查询天气。",
                  "originalRequest": "今晚八点告诉我天气",
                  "expectedToolCategories": ["information"]
                }
                """;
        tools.applyAutomationPlan(createPlan);
        AutomationStore.Reminder created = store.listReminders(AutomationStore.ReminderStatus.PENDING).getFirst();

        String updatePlan = """
                {
                  "operation": "UPDATE",
                  "targetId": "%s",
                  "kind": "LLM_TASK",
                  "runAt": "2026-07-22T21:00:00+08:00",
                  "instruction": "现在查询杭州市余杭区天气。"
                }
                """.formatted(created.id());

        String result = tools.applyAutomationPlan(updatePlan);

        assertThat(result).contains("updated");
        assertThat(store.listReminders(null)).hasSize(1);
        AutomationStore.Reminder updated = store.findReminder(created.id()).orElseThrow();
        assertThat(updated.remindAt()).isEqualTo(Instant.parse("2026-07-22T13:00:00Z"));
        assertThat(updated.instruction()).isEqualTo("现在查询杭州市余杭区天气。");
    }

    private static AutomationRuntime newRuntime(InMemoryAutomationStore store,
                                                RecordingReminderDispatcher dispatcher,
                                                ScheduledTaskExecutor executor) {
        return new AutomationRuntime(store, new RecordingReminderScheduler(), dispatcher,
                automationProperties(), FIXED_CLOCK, null, executor);
    }

    private static AutomationProperties automationProperties() {
        AutomationProperties properties = new AutomationProperties();
        properties.setDefaultRecipientId("bound-user@im.wechat");
        return properties;
    }

    private static class RecordingReminderScheduler implements AutomationRuntime.ReminderScheduler {
        @Override
        public void schedule(AutomationStore.Reminder reminder, Runnable task) {
        }

        @Override
        public boolean cancel(String reminderId) {
            return true;
        }
    }

    private static class RecordingReminderDispatcher implements AutomationRuntime.ReminderDispatcher {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void send(String recipientId, String message) {
            messages.add(message);
        }
    }

    private static class RecordingScheduledTaskExecutor implements ScheduledTaskExecutor {
        private final List<ScheduledTaskExecutionRequest> requests = new ArrayList<>();

        @Override
        public ScheduledTaskExecutionResult execute(ScheduledTaskExecutionRequest request) {
            requests.add(request);
            return ScheduledTaskExecutionResult.success("trigger-time weather result");
        }
    }

    private static class FailingScheduledTaskExecutor implements ScheduledTaskExecutor {
        private int attempts;

        @Override
        public ScheduledTaskExecutionResult execute(ScheduledTaskExecutionRequest request) {
            attempts++;
            return ScheduledTaskExecutionResult.failure("weather API unavailable");
        }
    }

    private static class InMemoryAutomationStore implements AutomationStore {
        private final List<Reminder> reminders = new ArrayList<>();
        private final List<ScheduleItem> scheduleItems = new ArrayList<>();
        private final List<RecurringTask> recurringTasks = new ArrayList<>();
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
                    .filter(item -> item.startAt().isBefore(toExclusive) && item.endAt().isAfter(fromInclusive))
                    .toList();
        }

        @Override
        public synchronized RecurringTask saveRecurringTask(RecurringTask task) {
            recurringTasks.removeIf(existing -> existing.id().equals(task.id()));
            recurringTasks.add(task);
            return task;
        }

        @Override
        public synchronized Optional<RecurringTask> findRecurringTask(String id) {
            return recurringTasks.stream().filter(task -> task.id().equals(id)).findFirst();
        }

        @Override
        public synchronized List<RecurringTask> listRecurringTasks(RecurringTaskStatus status) {
            if (status == null) {
                return List.copyOf(recurringTasks);
            }
            return recurringTasks.stream().filter(task -> task.status() == status).toList();
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
}
