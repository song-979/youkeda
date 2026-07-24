package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationWeatherActionTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-22T04:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @Test
    void weatherReminderDispatchesForecastWhenTriggered() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        WeatherTools weatherTools = new StubWeatherTools();
        AutomationRuntime runtime = newRuntime(store, scheduler, dispatcher, weatherTools);

        AutomationRuntime.ReminderResult created = runtime.createWeatherReminder(
                "杭州天气提醒",
                "2026-07-22T20:00:00+08:00",
                "杭州",
                "FORECAST",
                null);

        runtime.triggerReminder(created.reminder().id());

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.getFirst()).contains("FAKE_FORECAST:杭州");
        assertThat(store.findReminder(created.reminder().id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.SENT);
    }

    @Test
    void recurringWeatherReminderAdvancesNextRunAfterTrigger() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        WeatherTools weatherTools = new StubWeatherTools();
        AutomationRuntime runtime = newRuntime(store, scheduler, dispatcher, weatherTools);

        AutomationRuntime.RecurringTaskResult created = runtime.createRecurringWeatherReminder(
                "每日天气",
                AutomationStore.RecurringScheduleType.DAILY,
                "09:00",
                "杭州",
                "CURRENT",
                null);
        AutomationStore.Reminder firstInstance = store.listReminders(AutomationStore.ReminderStatus.PENDING).getFirst();

        runtime.triggerReminder(firstInstance.id());

        assertThat(dispatcher.messages).hasSize(1);
        assertThat(dispatcher.messages.getFirst()).contains("FAKE_CURRENT:杭州");
        assertThat(store.findRecurringTask(created.task().id()))
                .map(AutomationStore.RecurringTask::nextRunAt)
                .contains(Instant.parse("2026-07-24T01:00:00Z"));
        assertThat(store.listReminders(AutomationStore.ReminderStatus.PENDING))
                .extracting(AutomationStore.Reminder::recurringTaskId)
                .contains(created.task().id());
    }

    private static AutomationRuntime newRuntime(InMemoryAutomationStore store,
                                                RecordingReminderScheduler scheduler,
                                                RecordingReminderDispatcher dispatcher,
                                                WeatherTools weatherTools) {
        AutomationProperties properties = new AutomationProperties();
        properties.setDefaultRecipientId("bound-user@im.wechat");
        return new AutomationRuntime(store, scheduler, dispatcher, properties, FIXED_CLOCK, weatherTools);
    }

    private static class RecordingReminderScheduler implements AutomationRuntime.ReminderScheduler {
        private final List<String> scheduledIds = new ArrayList<>();

        @Override
        public void schedule(AutomationStore.Reminder reminder, Runnable task) {
            scheduledIds.add(reminder.id());
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
            return reminders.stream()
                    .filter(reminder -> status == null || reminder.status() == status)
                    .toList();
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
            return recurringTasks.stream()
                    .filter(task -> status == null || task.status() == status)
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

    private static class StubWeatherTools extends WeatherTools {
        StubWeatherTools() {
            super(new WeatherProperties());
        }

        @Override
        public String getCurrentWeather(String location) {
            return "FAKE_CURRENT:" + location;
        }

        @Override
        public String getWeatherForecast(String location) {
            return "FAKE_FORECAST:" + location;
        }
    }
}
