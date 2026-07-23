package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationRuntimeTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-22T04:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @Test
    void updateReminderChangesPendingReminderAndReschedulesIt() {
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, scheduler, new RecordingReminderDispatcher());
        AutomationRuntime.ReminderResult created = runtime.createReminder(
                "take medicine",
                "2026-07-22T20:00:00+08:00",
                "take medicine now");

        AutomationRuntime.ReminderResult updated = runtime.updateReminder(
                created.reminder().id(),
                "take vitamin",
                "2026-07-22T21:00:00+08:00",
                "take vitamin now");

        assertThat(updated.success()).isTrue();
        assertThat(updated.reminder().title()).isEqualTo("take vitamin");
        assertThat(updated.reminder().remindAt()).isEqualTo(Instant.parse("2026-07-22T13:00:00Z"));
        assertThat(updated.reminder().message()).isEqualTo("take vitamin now");
        assertThat(updated.reminder().status()).isEqualTo(AutomationStore.ReminderStatus.PENDING);
        assertThat(scheduler.cancelledIds).contains(created.reminder().id());
        assertThat(scheduler.scheduledIds).containsExactly(created.reminder().id(), created.reminder().id());
    }

    @Test
    void deleteReminderMarksItDeletedAndExcludesItFromPendingList() {
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, scheduler, new RecordingReminderDispatcher());
        AutomationRuntime.ReminderResult created = runtime.createReminder(
                "take medicine",
                "2026-07-22T20:00:00+08:00",
                "take medicine now");

        AutomationRuntime.ReminderResult deleted = runtime.deleteReminder(created.reminder().id());

        assertThat(deleted.success()).isTrue();
        assertThat(deleted.reminder().status()).isEqualTo(AutomationStore.ReminderStatus.DELETED);
        assertThat(runtime.listReminders(AutomationStore.ReminderStatus.PENDING)).isEmpty();
        assertThat(runtime.listReminders(AutomationStore.ReminderStatus.DELETED)).containsExactly(deleted.reminder());
        assertThat(scheduler.cancelledIds).contains(created.reminder().id());
    }

    @Test
    void cancelReminderDoesNotOverrideReminderThatIsAlreadyTriggering() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationStore.Reminder triggering = new AutomationStore.Reminder(
                "R-TRIGGER",
                "sending",
                Instant.parse("2026-07-22T04:01:00Z"),
                "sending now",
                AutomationStore.ReminderStatus.TRIGGERING,
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-22T04:00:30Z"),
                null,
                0);
        store.saveReminder(triggering);

        AutomationRuntime.ReminderResult result = newRuntime(
                store,
                new RecordingReminderScheduler(),
                new RecordingReminderDispatcher()).cancelReminder("R-TRIGGER");

        assertThat(result.success()).isFalse();
        assertThat(store.findReminder("R-TRIGGER"))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.TRIGGERING);
    }

    @Test
    void updateDeleteAndListScheduleItemsByStatus() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, new RecordingReminderScheduler(), new RecordingReminderDispatcher());
        AutomationRuntime.ScheduleResult created = runtime.createScheduleItem(
                "project sync",
                "2026-07-23T10:00:00+08:00",
                "2026-07-23T11:00:00+08:00",
                "weekly sync");

        AutomationRuntime.ScheduleResult updated = runtime.updateScheduleItem(
                created.item().id(),
                "project review",
                "2026-07-23T10:30:00+08:00",
                "2026-07-23T11:30:00+08:00",
                "review progress",
                AutomationStore.ScheduleItemStatus.COMPLETED);
        AutomationRuntime.ScheduleResult deleted = runtime.deleteScheduleItem(created.item().id());

        assertThat(updated.success()).isTrue();
        assertThat(updated.item().status()).isEqualTo(AutomationStore.ScheduleItemStatus.COMPLETED);
        assertThat(deleted.success()).isTrue();
        assertThat(deleted.item().status()).isEqualTo(AutomationStore.ScheduleItemStatus.DELETED);
        assertThat(runtime.listScheduleItems(
                "2026-07-23T00:00:00+08:00",
                "2026-07-24T00:00:00+08:00",
                AutomationStore.ScheduleItemStatus.DELETED)).containsExactly(deleted.item());
    }

    @Test
    void listScheduleItemsIncludesEventsThatOverlapRange() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, new RecordingReminderScheduler(), new RecordingReminderDispatcher());
        AutomationRuntime.ScheduleResult created = runtime.createScheduleItem(
                "overnight work",
                "2026-07-22T23:00:00+08:00",
                "2026-07-23T01:00:00+08:00",
                "");

        assertThat(runtime.listScheduleItems(
                "2026-07-23T00:30:00+08:00",
                "2026-07-23T02:00:00+08:00",
                AutomationStore.ScheduleItemStatus.ACTIVE)).containsExactly(created.item());
    }

    @Test
    void startupReschedulesFuturePendingRemindersAndMarksMissedOnes() {
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        store.saveReminder(new AutomationStore.Reminder(
                "R-FUTURE",
                "future",
                Instant.parse("2026-07-22T04:10:00Z"),
                "future message",
                AutomationStore.ReminderStatus.PENDING,
                Instant.parse("2026-07-22T03:00:00Z"),
                Instant.parse("2026-07-22T03:00:00Z"),
                null,
                0));
        store.saveReminder(new AutomationStore.Reminder(
                "R-MISSED",
                "missed",
                Instant.parse("2026-07-22T03:59:00Z"),
                "missed message",
                AutomationStore.ReminderStatus.PENDING,
                Instant.parse("2026-07-22T03:00:00Z"),
                Instant.parse("2026-07-22T03:00:00Z"),
                null,
                0));

        newRuntime(store, scheduler, new RecordingReminderDispatcher()).afterPropertiesSet();

        assertThat(scheduler.scheduledIds).containsExactly("R-FUTURE");
        assertThat(store.findReminder("R-MISSED"))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.MISSED);
    }

    @Test
    void duplicateReminderCreatedWithinThirtySecondsReturnsExistingReminder() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, new RecordingReminderScheduler(), new RecordingReminderDispatcher());

        AutomationRuntime.ReminderResult first = runtime.createReminder(
                "take medicine",
                "2026-07-22T20:00:00+08:00",
                "take medicine now");
        AutomationRuntime.ReminderResult second = runtime.createReminder(
                "take medicine",
                "2026-07-22T20:00:00+08:00",
                "take medicine now");

        assertThat(second.success()).isTrue();
        assertThat(second.reminder().id()).isEqualTo(first.reminder().id());
        assertThat(store.listReminders(AutomationStore.ReminderStatus.PENDING)).hasSize(1);
    }

    @Test
    void dailyRecurringReminderCreatesNextPendingReminderAndAdvancesAfterTrigger() {
        RecordingReminderScheduler scheduler = new RecordingReminderScheduler();
        RecordingReminderDispatcher dispatcher = new RecordingReminderDispatcher();
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, scheduler, dispatcher);

        AutomationRuntime.RecurringTaskResult created = runtime.createRecurringReminder(
                "daily standup",
                AutomationStore.RecurringScheduleType.DAILY,
                "09:30",
                "standup time");
        AutomationStore.Reminder firstInstance = store.listReminders(AutomationStore.ReminderStatus.PENDING).getFirst();

        assertThat(created.success()).isTrue();
        assertThat(created.task().nextRunAt()).isEqualTo(Instant.parse("2026-07-23T01:30:00Z"));
        assertThat(firstInstance.recurringTaskId()).isEqualTo(created.task().id());
        assertThat(firstInstance.remindAt()).isEqualTo(created.task().nextRunAt());
        assertThat(scheduler.scheduledIds).contains(firstInstance.id());

        runtime.triggerReminder(firstInstance.id());

        assertThat(store.findReminder(firstInstance.id()))
                .map(AutomationStore.Reminder::status)
                .contains(AutomationStore.ReminderStatus.SENT);
        assertThat(store.findRecurringTask(created.task().id()))
                .map(AutomationStore.RecurringTask::nextRunAt)
                .contains(Instant.parse("2026-07-24T01:30:00Z"));
        assertThat(store.listReminders(AutomationStore.ReminderStatus.PENDING))
                .extracting(AutomationStore.Reminder::remindAt)
                .contains(Instant.parse("2026-07-24T01:30:00Z"));
    }

    @Test
    void weeklyAndCronRecurringRemindersCalculateNextRun() {
        InMemoryAutomationStore store = new InMemoryAutomationStore();
        AutomationRuntime runtime = newRuntime(store, new RecordingReminderScheduler(), new RecordingReminderDispatcher());

        AutomationRuntime.RecurringTaskResult weekly = runtime.createRecurringReminder(
                "weekly review",
                AutomationStore.RecurringScheduleType.WEEKLY,
                "FRIDAY 18:00",
                "weekly review time");
        AutomationRuntime.RecurringTaskResult cron = runtime.createRecurringReminder(
                "cron review",
                AutomationStore.RecurringScheduleType.CRON,
                "0 15 10 * * *",
                "cron review time");

        assertThat(weekly.success()).isTrue();
        assertThat(weekly.task().nextRunAt()).isEqualTo(Instant.parse("2026-07-24T10:00:00Z"));
        assertThat(cron.success()).isTrue();
        assertThat(cron.task().nextRunAt()).isEqualTo(Instant.parse("2026-07-23T02:15:00Z"));
    }

    private static AutomationRuntime newRuntime(InMemoryAutomationStore store,
                                                RecordingReminderScheduler scheduler,
                                                RecordingReminderDispatcher dispatcher) {
        AutomationProperties properties = new AutomationProperties();
        properties.setDefaultRecipientId("bound-user@im.wechat");
        return new AutomationRuntime(store, scheduler, dispatcher, properties, FIXED_CLOCK);
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
        public synchronized Optional<ScheduleItem> findScheduleItem(String id) {
            return scheduleItems.stream().filter(item -> item.id().equals(id)).findFirst();
        }

        @Override
        public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive) {
            return listScheduleItems(fromInclusive, toExclusive, null);
        }

        @Override
        public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive,
                                                                 Instant toExclusive,
                                                                 ScheduleItemStatus status) {
            return scheduleItems.stream()
                    .filter(item -> status == null || effectiveStatus(item) == status)
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

        private ScheduleItemStatus effectiveStatus(ScheduleItem item) {
            return item.status() != null ? item.status() : ScheduleItemStatus.ACTIVE;
        }
    }
}
