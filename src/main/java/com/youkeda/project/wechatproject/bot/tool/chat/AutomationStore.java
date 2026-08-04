package com.youkeda.project.wechatproject.bot.tool.chat;

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

    /**
     * Atomically transition a reminder from {@code expectedStatus} to {@code newStatus}.
     * Returns the updated reminder, or empty when the reminder does not exist or its
     * current status does not match (i.e. another thread already claimed it).
     * <p>
     * The default implementation is a non-atomic read-modify-write; stores with
     * stronger guarantees (like {@code JsonAutomationStore}) override it with a
     * synchronized version so trigger claiming cannot double-fire.
     */
    default Optional<Reminder> transitionReminderStatus(String id, ReminderStatus expectedStatus,
                                                        ReminderStatus newStatus, Instant updatedAt) {
        Optional<Reminder> existing = findReminder(id);
        if (existing.isEmpty() || existing.get().status() != expectedStatus) {
            return Optional.empty();
        }
        Reminder r = existing.get();
        Reminder updated = new Reminder(r.id(), r.title(), r.remindAt(), r.message(), newStatus,
                r.createdAt(), updatedAt, r.failureMessage(), r.sendAttempts(), r.actionType(),
                r.actionTarget(), r.recurringTaskId(), r.taskKind(), r.instruction(), r.originalRequest(),
                r.expectedToolCategories(), r.maxRetries(), r.ownerId());
        return Optional.of(saveReminder(updated));
    }

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

    enum AutomationActionType {
        TEXT,
        WEATHER_CURRENT,
        WEATHER_FORECAST
    }

    enum AutomationTaskKind {
        TEXT_REMINDER,
        LLM_TASK
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
            AutomationActionType actionType,
            String actionTarget,
            String recurringTaskId,
            AutomationTaskKind taskKind,
            String instruction,
            String originalRequest,
            List<String> expectedToolCategories,
            int maxRetries,
            String ownerId) {

        public Reminder {
            taskKind = taskKind != null ? taskKind : AutomationTaskKind.TEXT_REMINDER;
            expectedToolCategories = expectedToolCategories != null ? List.copyOf(expectedToolCategories) : List.of();
        }

        public Reminder withOwnerId(String newOwnerId) {
            return new Reminder(id, title, remindAt, message, status, createdAt, updatedAt,
                    failureMessage, sendAttempts, actionType, actionTarget, recurringTaskId, taskKind,
                    instruction, originalRequest, expectedToolCategories, maxRetries, newOwnerId);
        }

        /** Backward-compatible constructor without ownerId (legacy single-recipient behavior). */
        public Reminder(String id,
                        String title,
                        Instant remindAt,
                        String message,
                        ReminderStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        String failureMessage,
                        int sendAttempts,
                        AutomationActionType actionType,
                        String actionTarget,
                        String recurringTaskId,
                        AutomationTaskKind taskKind,
                        String instruction,
                        String originalRequest,
                        List<String> expectedToolCategories,
                        int maxRetries) {
            this(id, title, remindAt, message, status, createdAt, updatedAt, failureMessage, sendAttempts,
                    actionType, actionTarget, recurringTaskId, taskKind, instruction, originalRequest,
                    expectedToolCategories, maxRetries, null);
        }

        public Reminder(String id,
                        String title,
                        Instant remindAt,
                        String message,
                        ReminderStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        String failureMessage,
                        int sendAttempts) {
            this(id, title, remindAt, message, status, createdAt, updatedAt, failureMessage, sendAttempts,
                    AutomationActionType.TEXT, null, null);
        }

        public Reminder(String id,
                        String title,
                        Instant remindAt,
                        String message,
                        ReminderStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        String failureMessage,
                        int sendAttempts,
                        String recurringTaskId) {
            this(id, title, remindAt, message, status, createdAt, updatedAt, failureMessage, sendAttempts,
                    AutomationActionType.TEXT, null, recurringTaskId);
        }

        public Reminder(String id,
                        String title,
                        Instant remindAt,
                        String message,
                        ReminderStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        String failureMessage,
                        int sendAttempts,
                        AutomationActionType actionType,
                        String actionTarget,
                        String recurringTaskId) {
            this(id, title, remindAt, message, status, createdAt, updatedAt, failureMessage, sendAttempts,
                    actionType, actionTarget, recurringTaskId, AutomationTaskKind.TEXT_REMINDER,
                    null, null, List.of(), 0);
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
            String failureMessage,
            AutomationActionType actionType,
            String actionTarget,
            AutomationTaskKind taskKind,
            String instruction,
            String originalRequest,
            List<String> expectedToolCategories,
            int maxRetries,
            String ownerId) {

        public RecurringTask {
            taskKind = taskKind != null ? taskKind : AutomationTaskKind.TEXT_REMINDER;
            expectedToolCategories = expectedToolCategories != null ? List.copyOf(expectedToolCategories) : List.of();
        }

        public RecurringTask withOwnerId(String newOwnerId) {
            return new RecurringTask(id, title, scheduleType, scheduleExpression, message, timeZone,
                    nextRunAt, status, createdAt, updatedAt, failureMessage, actionType, actionTarget,
                    taskKind, instruction, originalRequest, expectedToolCategories, maxRetries, newOwnerId);
        }

        /** Backward-compatible constructor without ownerId. */
        public RecurringTask(String id,
                             String title,
                             RecurringScheduleType scheduleType,
                             String scheduleExpression,
                             String message,
                             String timeZone,
                             Instant nextRunAt,
                             RecurringTaskStatus status,
                             Instant createdAt,
                             Instant updatedAt,
                             String failureMessage,
                             AutomationActionType actionType,
                             String actionTarget,
                             AutomationTaskKind taskKind,
                             String instruction,
                             String originalRequest,
                             List<String> expectedToolCategories,
                             int maxRetries) {
            this(id, title, scheduleType, scheduleExpression, message, timeZone, nextRunAt, status,
                    createdAt, updatedAt, failureMessage, actionType, actionTarget, taskKind,
                    instruction, originalRequest, expectedToolCategories, maxRetries, null);
        }

        public RecurringTask(String id,
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
            this(id, title, scheduleType, scheduleExpression, message, timeZone, nextRunAt, status,
                    createdAt, updatedAt, failureMessage, AutomationActionType.TEXT, null);
        }

        public RecurringTask(String id,
                             String title,
                             RecurringScheduleType scheduleType,
                             String scheduleExpression,
                             String message,
                             String timeZone,
                             Instant nextRunAt,
                             RecurringTaskStatus status,
                             Instant createdAt,
                             Instant updatedAt,
                             String failureMessage,
                             AutomationActionType actionType,
                             String actionTarget) {
            this(id, title, scheduleType, scheduleExpression, message, timeZone, nextRunAt, status,
                    createdAt, updatedAt, failureMessage, actionType, actionTarget,
                    AutomationTaskKind.TEXT_REMINDER, null, null, List.of(), 0);
        }
    }

    record RecipientBinding(String recipientId, Instant boundAt, Instant updatedAt) {
    }

    static ScheduleItemStatus effectiveStatus(ScheduleItem item) {
        return item.status() != null ? item.status() : ScheduleItemStatus.ACTIVE;
    }
}
