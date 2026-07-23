package com.youkeda.project.wechatproject.bot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class AutomationRuntime implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuntime.class);

    private final AutomationStore store;
    private final ReminderScheduler scheduler;
    private final ReminderDispatcher dispatcher;
    private final AutomationProperties properties;
    private final Clock clock;
    private final ZoneId zoneId;

    public AutomationRuntime(AutomationStore store,
                             ReminderScheduler scheduler,
                             ReminderDispatcher dispatcher,
                             AutomationProperties properties,
                             Clock clock) {
        this.store = store;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
        this.zoneId = ZoneId.of(properties.getTimeZone());
    }

    @Override
    public void afterPropertiesSet() {
        reschedulePendingReminders();
        scheduleRecurringTasks();
    }

    public ReminderResult createReminder(String title, String remindAtText, String message) {
        String normalizedTitle = normalizeRequired(title, "title");
        String normalizedMessage = normalizeOptional(message, normalizedTitle);
        if (normalizedTitle == null) {
            return ReminderResult.failure("title is required");
        }

        Instant remindAt;
        try {
            remindAt = parseInstant(remindAtText);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return ReminderResult.failure("remindAt must be an ISO datetime, for example 2026-07-22T20:00:00+08:00");
        }

        Instant now = clock.instant();
        if (!remindAt.isAfter(now)) {
            return ReminderResult.failure("remindAt must be in the future");
        }
        if (resolveRecipientId() == null) {
            return ReminderResult.failure("reminder recipient is not bound yet");
        }

        Optional<AutomationStore.Reminder> duplicate = findRecentDuplicate(normalizedTitle, remindAt, normalizedMessage, now);
        if (duplicate.isPresent()) {
            return ReminderResult.success(duplicate.get(), "duplicate reminder already exists");
        }

        AutomationStore.Reminder reminder = new AutomationStore.Reminder(
                newReminderId(),
                normalizedTitle,
                remindAt,
                normalizedMessage,
                AutomationStore.ReminderStatus.PENDING,
                now,
                now,
                null,
                0,
                null);
        store.saveReminder(reminder);
        scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
        return ReminderResult.success(reminder, "reminder created");
    }

    public List<AutomationStore.Reminder> listReminders(AutomationStore.ReminderStatus status) {
        return store.listReminders(status);
    }

    public ReminderResult cancelReminder(String id) {
        Optional<AutomationStore.Reminder> existing = store.findReminder(id);
        if (existing.isEmpty()) {
            return ReminderResult.failure("reminder not found: " + id);
        }
        AutomationStore.Reminder reminder = existing.get();
        if (reminder.status() != AutomationStore.ReminderStatus.PENDING) {
            return ReminderResult.failure("reminder cannot be cancelled because it is " + reminder.status());
        }
        AutomationStore.Reminder cancelled = copyReminder(
                reminder,
                AutomationStore.ReminderStatus.CANCELLED,
                null,
                reminder.sendAttempts());
        store.saveReminder(cancelled);
        scheduler.cancel(reminder.id());
        return ReminderResult.success(cancelled, "reminder cancelled");
    }

    public ReminderResult updateReminder(String id, String title, String remindAtText, String message) {
        Optional<AutomationStore.Reminder> existing = store.findReminder(id);
        if (existing.isEmpty()) {
            return ReminderResult.failure("reminder not found: " + id);
        }
        AutomationStore.Reminder reminder = existing.get();
        if (reminder.status() != AutomationStore.ReminderStatus.PENDING) {
            return ReminderResult.failure("reminder cannot be updated because it is " + reminder.status());
        }

        String normalizedTitle = normalizeOptional(title, reminder.title());
        String normalizedMessage = normalizeOptional(message, reminder.message());
        Instant remindAt = reminder.remindAt();
        if (remindAtText != null && !remindAtText.isBlank()) {
            try {
                remindAt = parseInstant(remindAtText);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                return ReminderResult.failure("remindAt must be an ISO datetime, for example 2026-07-22T20:00:00+08:00");
            }
            if (!remindAt.isAfter(clock.instant())) {
                return ReminderResult.failure("remindAt must be in the future");
            }
        }

        AutomationStore.Reminder updated = new AutomationStore.Reminder(
                reminder.id(),
                normalizedTitle,
                remindAt,
                normalizedMessage,
                reminder.status(),
                reminder.createdAt(),
                clock.instant(),
                null,
                reminder.sendAttempts(),
                reminder.recurringTaskId());
        store.saveReminder(updated);
        scheduler.cancel(updated.id());
        scheduler.schedule(updated, () -> triggerReminder(updated.id()));
        return ReminderResult.success(updated, "reminder updated");
    }

    public ReminderResult deleteReminder(String id) {
        Optional<AutomationStore.Reminder> existing = store.findReminder(id);
        if (existing.isEmpty()) {
            return ReminderResult.failure("reminder not found: " + id);
        }
        AutomationStore.Reminder reminder = existing.get();
        if (reminder.status() == AutomationStore.ReminderStatus.TRIGGERING) {
            return ReminderResult.failure("reminder cannot be deleted because it is TRIGGERING");
        }
        AutomationStore.Reminder deleted = copyReminder(
                reminder,
                AutomationStore.ReminderStatus.DELETED,
                null,
                reminder.sendAttempts());
        store.saveReminder(deleted);
        scheduler.cancel(reminder.id());
        return ReminderResult.success(deleted, "reminder deleted");
    }

    public ScheduleResult createScheduleItem(String title, String startAtText, String endAtText, String notes) {
        String normalizedTitle = normalizeRequired(title, "title");
        if (normalizedTitle == null) {
            return ScheduleResult.failure("title is required");
        }

        Instant startAt;
        Instant endAt;
        try {
            startAt = parseInstant(startAtText);
            endAt = parseInstant(endAtText);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return ScheduleResult.failure("startAt and endAt must be ISO datetimes");
        }
        if (!endAt.isAfter(startAt)) {
            return ScheduleResult.failure("endAt must be after startAt");
        }

        Instant now = clock.instant();
        AutomationStore.ScheduleItem item = new AutomationStore.ScheduleItem(
                newScheduleId(),
                normalizedTitle,
                startAt,
                endAt,
                normalizeOptional(notes, ""),
                AutomationStore.ScheduleItemStatus.ACTIVE,
                now,
                now);
        store.saveScheduleItem(item);
        return ScheduleResult.success(item, "schedule item created");
    }

    public List<AutomationStore.ScheduleItem> listScheduleItems(String fromText, String toText) {
        return store.listScheduleItems(parseInstant(fromText), parseInstant(toText));
    }

    public List<AutomationStore.ScheduleItem> listScheduleItems(String fromText,
                                                                String toText,
                                                                AutomationStore.ScheduleItemStatus status) {
        return store.listScheduleItems(parseInstant(fromText), parseInstant(toText), status);
    }

    public ScheduleResult updateScheduleItem(String id,
                                             String title,
                                             String startAtText,
                                             String endAtText,
                                             String notes,
                                             AutomationStore.ScheduleItemStatus status) {
        Optional<AutomationStore.ScheduleItem> existing = store.findScheduleItem(id);
        if (existing.isEmpty()) {
            return ScheduleResult.failure("schedule item not found: " + id);
        }
        AutomationStore.ScheduleItem item = existing.get();
        Instant startAt = item.startAt();
        Instant endAt = item.endAt();
        try {
            if (startAtText != null && !startAtText.isBlank()) {
                startAt = parseInstant(startAtText);
            }
            if (endAtText != null && !endAtText.isBlank()) {
                endAt = parseInstant(endAtText);
            }
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return ScheduleResult.failure("startAt and endAt must be ISO datetimes");
        }
        if (!endAt.isAfter(startAt)) {
            return ScheduleResult.failure("endAt must be after startAt");
        }
        AutomationStore.ScheduleItem updated = new AutomationStore.ScheduleItem(
                item.id(),
                normalizeOptional(title, item.title()),
                startAt,
                endAt,
                normalizeOptional(notes, item.notes()),
                status != null ? status : effectiveScheduleStatus(item),
                item.createdAt(),
                clock.instant());
        store.saveScheduleItem(updated);
        return ScheduleResult.success(updated, "schedule item updated");
    }

    public ScheduleResult deleteScheduleItem(String id) {
        Optional<AutomationStore.ScheduleItem> existing = store.findScheduleItem(id);
        if (existing.isEmpty()) {
            return ScheduleResult.failure("schedule item not found: " + id);
        }
        AutomationStore.ScheduleItem item = existing.get();
        AutomationStore.ScheduleItem deleted = new AutomationStore.ScheduleItem(
                item.id(),
                item.title(),
                item.startAt(),
                item.endAt(),
                item.notes(),
                AutomationStore.ScheduleItemStatus.DELETED,
                item.createdAt(),
                clock.instant());
        store.saveScheduleItem(deleted);
        return ScheduleResult.success(deleted, "schedule item deleted");
    }

    public RecurringTaskResult createRecurringReminder(String title,
                                                       AutomationStore.RecurringScheduleType scheduleType,
                                                       String scheduleExpression,
                                                       String message) {
        String normalizedTitle = normalizeRequired(title, "title");
        String normalizedExpression = normalizeRequired(scheduleExpression, "scheduleExpression");
        if (normalizedTitle == null) {
            return RecurringTaskResult.failure("title is required");
        }
        if (scheduleType == null) {
            return RecurringTaskResult.failure("scheduleType is required");
        }
        if (normalizedExpression == null) {
            return RecurringTaskResult.failure("scheduleExpression is required");
        }
        if (resolveRecipientId() == null) {
            return RecurringTaskResult.failure("reminder recipient is not bound yet");
        }

        Instant now = clock.instant();
        Instant nextRunAt;
        try {
            nextRunAt = computeNextRunAt(scheduleType, normalizedExpression, now);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return RecurringTaskResult.failure(e.getMessage());
        }
        AutomationStore.RecurringTask task = new AutomationStore.RecurringTask(
                newRecurringTaskId(),
                normalizedTitle,
                scheduleType,
                normalizedExpression,
                normalizeOptional(message, normalizedTitle),
                zoneId.getId(),
                nextRunAt,
                AutomationStore.RecurringTaskStatus.ACTIVE,
                now,
                now,
                null);
        store.saveRecurringTask(task);
        scheduleRecurringInstance(task);
        return RecurringTaskResult.success(task, "recurring reminder created");
    }

    public RecurringTaskResult deleteRecurringTask(String id) {
        Optional<AutomationStore.RecurringTask> existing = store.findRecurringTask(id);
        if (existing.isEmpty()) {
            return RecurringTaskResult.failure("recurring task not found: " + id);
        }
        AutomationStore.RecurringTask task = existing.get();
        AutomationStore.RecurringTask deleted = copyRecurringTask(
                task,
                task.nextRunAt(),
                AutomationStore.RecurringTaskStatus.DELETED,
                null);
        store.saveRecurringTask(deleted);
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            if (id.equals(reminder.recurringTaskId())) {
                deleteReminder(reminder.id());
            }
        }
        return RecurringTaskResult.success(deleted, "recurring task deleted");
    }

    public List<AutomationStore.RecurringTask> listRecurringTasks(AutomationStore.RecurringTaskStatus status) {
        return store.listRecurringTasks(status);
    }

    void triggerReminder(String reminderId) {
        Optional<AutomationStore.Reminder> existing = store.findReminder(reminderId);
        if (existing.isEmpty()) {
            return;
        }
        AutomationStore.Reminder reminder = existing.get();
        if (reminder.status() != AutomationStore.ReminderStatus.PENDING) {
            return;
        }

        AutomationStore.Reminder triggering = copyReminder(
                reminder,
                AutomationStore.ReminderStatus.TRIGGERING,
                null,
                reminder.sendAttempts());
        store.saveReminder(triggering);

        String recipientId = resolveRecipientId();
        if (recipientId == null) {
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.FAILED,
                    "reminder recipient is not bound",
                    triggering.sendAttempts()));
            return;
        }

        int attempts = Math.max(1, properties.getMaxSendAttempts());
        Exception lastError = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                dispatcher.send(recipientId, formatReminderMessage(triggering));
                store.saveReminder(copyReminder(triggering, AutomationStore.ReminderStatus.SENT, null, i));
                advanceRecurringTaskIfNeeded(triggering);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("reminder dispatch failed: id={}, attempt={}/{}", reminderId, i, attempts, e);
                if (isRetryableError(e) && i < attempts) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (isRetryableError(lastError)) {
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.PENDING,
                    "waiting for context refresh: " + (lastError != null ? lastError.getMessage() : "send failed"),
                    0));
            log.info("reminder {} kept PENDING due to retryable error, will retry on next incoming message", reminderId);
        } else {
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.FAILED,
                    lastError != null ? lastError.getMessage() : "send failed",
                    attempts));
        }
    }

    private static boolean isRetryableError(Exception e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("context token") || msg.contains("contextToken"));
    }

    public void retryOverduePendingReminders() {
        String recipientId = resolveRecipientId();
        if (recipientId == null) {
            return;
        }
        retryOverduePendingReminders(recipientId);
    }

    public void retryOverduePendingReminders(String triggeredByUserId) {
        if (triggeredByUserId == null || triggeredByUserId.isBlank()) {
            return;
        }
        String recipientId = resolveRecipientId();
        if (recipientId == null || !recipientId.equals(triggeredByUserId)) {
            return;
        }
        Instant now = clock.instant();
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            if (!reminder.remindAt().isAfter(now)) {
                log.info("retrying overdue pending reminder: id={}, title={}", reminder.id(), reminder.title());
                triggerReminder(reminder.id());
            }
        }
    }

    private void reschedulePendingReminders() {
        Instant now = clock.instant();
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            if (reminder.remindAt().isAfter(now)) {
                scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
            } else if (properties.isSendMissedRemindersOnStartup()) {
                triggerReminder(reminder.id());
            } else {
                store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.MISSED,
                        "missed while application was offline", reminder.sendAttempts()));
            }
        }
    }

    private void scheduleRecurringTasks() {
        Instant now = clock.instant();
        for (AutomationStore.RecurringTask task : store.listRecurringTasks(AutomationStore.RecurringTaskStatus.ACTIVE)) {
            AutomationStore.RecurringTask normalized = task;
            if (!task.nextRunAt().isAfter(now)) {
                Instant nextRunAt = computeNextRunAt(task.scheduleType(), task.scheduleExpression(), now);
                normalized = copyRecurringTask(task, nextRunAt, AutomationStore.RecurringTaskStatus.ACTIVE, null);
                store.saveRecurringTask(normalized);
            }
            if (findPendingRecurringInstance(normalized.id(), normalized.nextRunAt()).isEmpty()) {
                scheduleRecurringInstance(normalized);
            }
        }
    }

    private Optional<AutomationStore.Reminder> findRecentDuplicate(String title, Instant remindAt, String message, Instant now) {
        return store.listReminders(AutomationStore.ReminderStatus.PENDING).stream()
                .filter(reminder -> reminder.title().equals(title))
                .filter(reminder -> reminder.remindAt().equals(remindAt))
                .filter(reminder -> reminder.message().equals(message))
                .filter(reminder -> reminder.createdAt().plusSeconds(30).isAfter(now))
                .findFirst();
    }

    private String resolveRecipientId() {
        String configured = properties.getDefaultRecipientId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return store.getRecipientBinding()
                .map(AutomationStore.RecipientBinding::recipientId)
                .filter(recipientId -> !recipientId.isBlank())
                .orElse(null);
    }

    private AutomationStore.Reminder copyReminder(AutomationStore.Reminder reminder,
                                                  AutomationStore.ReminderStatus status,
                                                  String failureMessage,
                                                  int sendAttempts) {
        return new AutomationStore.Reminder(
                reminder.id(),
                reminder.title(),
                reminder.remindAt(),
                reminder.message(),
                status,
                reminder.createdAt(),
                clock.instant(),
                failureMessage,
                sendAttempts,
                reminder.recurringTaskId());
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("datetime is required");
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return LocalDateTime.parse(trimmed).atZone(zoneId).toInstant();
            }
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeOptional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String newReminderId() {
        return "R-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String newScheduleId() {
        return "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String newRecurringTaskId() {
        return "RR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private Instant computeNextRunAt(AutomationStore.RecurringScheduleType scheduleType,
                                     String scheduleExpression,
                                     Instant after) {
        return switch (scheduleType) {
            case DAILY -> nextDailyRun(scheduleExpression, after);
            case WEEKLY -> nextWeeklyRun(scheduleExpression, after);
            case CRON -> nextCronRun(scheduleExpression, after);
        };
    }

    private Instant nextDailyRun(String expression, Instant after) {
        LocalTime time = LocalTime.parse(expression.trim());
        ZonedDateTime base = after.atZone(zoneId);
        ZonedDateTime candidate = base.toLocalDate().atTime(time).atZone(zoneId);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private Instant nextWeeklyRun(String expression, Instant after) {
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("weekly scheduleExpression must be like FRIDAY 18:00");
        }
        DayOfWeek dayOfWeek = DayOfWeek.valueOf(parts[0].toUpperCase(Locale.ROOT));
        LocalTime time = LocalTime.parse(parts[1]);
        ZonedDateTime base = after.atZone(zoneId);
        ZonedDateTime candidate = base.toLocalDate().atTime(time).atZone(zoneId);
        int daysUntilTarget = dayOfWeek.getValue() - base.getDayOfWeek().getValue();
        if (daysUntilTarget < 0) {
            daysUntilTarget += 7;
        }
        candidate = candidate.plusDays(daysUntilTarget);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
    }

    private Instant nextCronRun(String expression, Instant after) {
        CronExpression cronExpression = CronExpression.parse(expression.trim());
        ZonedDateTime next = cronExpression.next(after.atZone(zoneId));
        if (next == null) {
            throw new IllegalArgumentException("cron scheduleExpression has no next run");
        }
        return next.toInstant();
    }

    private void scheduleRecurringInstance(AutomationStore.RecurringTask task) {
        AutomationStore.Reminder reminder = new AutomationStore.Reminder(
                newReminderId(),
                task.title(),
                task.nextRunAt(),
                task.message(),
                AutomationStore.ReminderStatus.PENDING,
                clock.instant(),
                clock.instant(),
                null,
                0,
                task.id());
        store.saveReminder(reminder);
        scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
    }

    private void advanceRecurringTaskIfNeeded(AutomationStore.Reminder reminder) {
        if (reminder.recurringTaskId() == null || reminder.recurringTaskId().isBlank()) {
            return;
        }
        Optional<AutomationStore.RecurringTask> existing = store.findRecurringTask(reminder.recurringTaskId());
        if (existing.isEmpty() || existing.get().status() != AutomationStore.RecurringTaskStatus.ACTIVE) {
            return;
        }
        AutomationStore.RecurringTask task = existing.get();
        Instant nextRunAt = computeNextRunAt(task.scheduleType(), task.scheduleExpression(), task.nextRunAt());
        AutomationStore.RecurringTask advanced = copyRecurringTask(
                task,
                nextRunAt,
                AutomationStore.RecurringTaskStatus.ACTIVE,
                null);
        store.saveRecurringTask(advanced);
        scheduleRecurringInstance(advanced);
    }

    private Optional<AutomationStore.Reminder> findPendingRecurringInstance(String recurringTaskId, Instant remindAt) {
        return store.listReminders(AutomationStore.ReminderStatus.PENDING).stream()
                .filter(reminder -> recurringTaskId.equals(reminder.recurringTaskId()))
                .filter(reminder -> reminder.remindAt().equals(remindAt))
                .findFirst();
    }

    private AutomationStore.RecurringTask copyRecurringTask(AutomationStore.RecurringTask task,
                                                           Instant nextRunAt,
                                                           AutomationStore.RecurringTaskStatus status,
                                                           String failureMessage) {
        return new AutomationStore.RecurringTask(
                task.id(),
                task.title(),
                task.scheduleType(),
                task.scheduleExpression(),
                task.message(),
                task.timeZone(),
                nextRunAt,
                status,
                task.createdAt(),
                clock.instant(),
                failureMessage);
    }

    private static AutomationStore.ScheduleItemStatus effectiveScheduleStatus(AutomationStore.ScheduleItem item) {
        return item.status() != null ? item.status() : AutomationStore.ScheduleItemStatus.ACTIVE;
    }

    private String formatReminderMessage(AutomationStore.Reminder reminder) {
        return "Reminder: " + reminder.title() + "\n"
                + "Time: " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(reminder.remindAt().atZone(zoneId)) + "\n"
                + "Message: " + reminder.message();
    }

    public interface ReminderScheduler {
        void schedule(AutomationStore.Reminder reminder, Runnable task);

        boolean cancel(String reminderId);
    }

    public interface ReminderDispatcher {
        void send(String recipientId, String message) throws Exception;
    }

    public record ReminderResult(boolean success, AutomationStore.Reminder reminder, String message) {
        static ReminderResult success(AutomationStore.Reminder reminder, String message) {
            return new ReminderResult(true, reminder, message);
        }

        static ReminderResult failure(String message) {
            return new ReminderResult(false, null, message);
        }
    }

    public record ScheduleResult(boolean success, AutomationStore.ScheduleItem item, String message) {
        static ScheduleResult success(AutomationStore.ScheduleItem item, String message) {
            return new ScheduleResult(true, item, message);
        }

        static ScheduleResult failure(String message) {
            return new ScheduleResult(false, null, message);
        }
    }

    public record RecurringTaskResult(boolean success, AutomationStore.RecurringTask task, String message) {
        static RecurringTaskResult success(AutomationStore.RecurringTask task, String message) {
            return new RecurringTaskResult(true, task, message);
        }

        static RecurringTaskResult failure(String message) {
            return new RecurringTaskResult(false, null, message);
        }
    }

    public static class SpringReminderScheduler implements ReminderScheduler {
        private final TaskScheduler taskScheduler;
        private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

        public SpringReminderScheduler(TaskScheduler taskScheduler) {
            this.taskScheduler = taskScheduler;
        }

        @Override
        public void schedule(AutomationStore.Reminder reminder, Runnable task) {
            cancel(reminder.id());
            ScheduledFuture<?> future = taskScheduler.schedule(task, reminder.remindAt());
            if (future != null) {
                futures.put(reminder.id(), future);
            }
        }

        @Override
        public boolean cancel(String reminderId) {
            ScheduledFuture<?> future = futures.remove(reminderId);
            return future != null && future.cancel(false);
        }
    }
}
