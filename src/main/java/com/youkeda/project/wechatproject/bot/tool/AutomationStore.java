package com.youkeda.project.wechatproject.bot.tool;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationStore {

    Reminder saveReminder(Reminder reminder);

    Optional<Reminder> findReminder(String id);

    List<Reminder> listReminders(ReminderStatus status);

    ScheduleItem saveScheduleItem(ScheduleItem item);

    default Optional<ScheduleItem> findScheduleItem(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return listScheduleItems(Instant.MIN, Instant.MAX).stream()
                .filter(item -> item.id().equals(id))
                .findFirst();
    }

    List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive);

    default List<ScheduleItem> listScheduleItems(Instant fromInclusive,
                                                 Instant toExclusive,
                                                 ScheduleItemStatus status) {
        return listScheduleItems(fromInclusive, toExclusive).stream()
                .filter(item -> status == null || effectiveStatus(item) == status)
                .toList();
    }

    default RecurringTask saveRecurringTask(RecurringTask task) {
        throw new UnsupportedOperationException("recurring tasks are not supported by this store");
    }

    default Optional<RecurringTask> findRecurringTask(String id) {
        return Optional.empty();
    }

    default List<RecurringTask> listRecurringTasks(RecurringTaskStatus status) {
        return List.of();
    }

    Optional<RecipientBinding> getRecipientBinding();

    RecipientBinding saveRecipientBinding(RecipientBinding binding);

    enum ReminderStatus {
        PENDING,
        TRIGGERING,
        SENT,
        CANCELLED,
        FAILED,
        MISSED,
        DELETED
    }

    enum ScheduleItemStatus {
        ACTIVE,
        CANCELLED,
        COMPLETED,
        DELETED
    }

    enum RecurringScheduleType {
        DAILY,
        WEEKLY,
        CRON
    }

    enum RecurringTaskStatus {
        ACTIVE,
        PAUSED,
        CANCELLED,
        DELETED
    }

    record Reminder(
            String id,
            String title,
            Instant remindAt,
            String message,
            ReminderStatus status,
            Instant createdAt,
            Instant updatedAt,
            String failureMessage,
            int sendAttempts,
            String recurringTaskId) {

        public Reminder(String id,
                        String title,
                        Instant remindAt,
                        String message,
                        ReminderStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        String failureMessage,
                        int sendAttempts) {
            this(id, title, remindAt, message, status, createdAt, updatedAt, failureMessage, sendAttempts, null);
        }
    }

    record ScheduleItem(
            String id,
            String title,
            Instant startAt,
            Instant endAt,
            String notes,
            ScheduleItemStatus status,
            Instant createdAt,
            Instant updatedAt) {

        public ScheduleItem(String id,
                            String title,
                            Instant startAt,
                            Instant endAt,
                            String notes,
                            Instant createdAt,
                            Instant updatedAt) {
            this(id, title, startAt, endAt, notes, ScheduleItemStatus.ACTIVE, createdAt, updatedAt);
        }
    }

    record RecurringTask(
            String id,
            String title,
            RecurringScheduleType scheduleType,
            String scheduleExpression,
            String message,
            String timeZone,
            Instant nextRunAt,
            RecurringTaskStatus status,
            Instant createdAt,
            Instant updatedAt,
            String failureMessage) {
    }

    record RecipientBinding(String recipientId, Instant boundAt, Instant updatedAt) {
    }

    static ScheduleItemStatus effectiveStatus(ScheduleItem item) {
        return item.status() != null ? item.status() : ScheduleItemStatus.ACTIVE;
    }
}
