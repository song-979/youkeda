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
    private final WeatherTools weatherTools;
    private final ScheduledTaskExecutor scheduledTaskExecutor;

    public AutomationRuntime(AutomationStore store,
                             ReminderScheduler scheduler,
                             ReminderDispatcher dispatcher,
                             AutomationProperties properties,
                             Clock clock) {
        this(store, scheduler, dispatcher, properties, clock, null);
    }

    public AutomationRuntime(AutomationStore store,
                             ReminderScheduler scheduler,
                             ReminderDispatcher dispatcher,
                             AutomationProperties properties,
                             Clock clock,
                             WeatherTools weatherTools) {
        this(store, scheduler, dispatcher, properties, clock, weatherTools, null);
    }

    public AutomationRuntime(AutomationStore store,
                             ReminderScheduler scheduler,
                             ReminderDispatcher dispatcher,
                             AutomationProperties properties,
                             Clock clock,
                             WeatherTools weatherTools,
                             ScheduledTaskExecutor scheduledTaskExecutor) {
        this.store = store;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
        this.zoneId = ZoneId.of(properties.getTimeZone());
        this.weatherTools = weatherTools;
        this.scheduledTaskExecutor = scheduledTaskExecutor;
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
                AutomationStore.AutomationActionType.TEXT,
                null,
                null);
        store.saveReminder(reminder);
        scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
        return ReminderResult.success(reminder, "reminder created");
    }

    public ReminderResult createWeatherReminder(String title,
                                                String remindAtText,
                                                String location,
                                                String weatherMode,
                                                String message) {
        AutomationStore.AutomationActionType actionType = parseWeatherActionType(weatherMode);
        if (actionType == null) {
            return ReminderResult.failure("weatherMode must be CURRENT or FORECAST");
        }
        String normalizedLocation = normalizeRequired(location, "location");
        if (normalizedLocation == null) {
            return ReminderResult.failure("location is required");
        }
        return createActionReminder(title, remindAtText, normalizeOptional(message, title), actionType, normalizedLocation);
    }

    public ReminderResult createLlmTask(String title,
                                        String runAtText,
                                        String instruction,
                                        String originalRequest,
                                        List<String> expectedToolCategories) {
        String normalizedTitle = normalizeRequired(title, "title");
        String normalizedInstruction = normalizeRequired(instruction, "instruction");
        if (normalizedTitle == null) {
            return ReminderResult.failure("title is required");
        }
        if (normalizedInstruction == null) {
            return ReminderResult.failure("instruction is required");
        }
        return createTaskReminder(
                normalizedTitle,
                runAtText,
                normalizedTitle,
                AutomationStore.AutomationTaskKind.LLM_TASK,
                normalizedInstruction,
                normalizeOptional(originalRequest, normalizedInstruction),
                expectedToolCategories,
                2);
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
                effectiveActionType(reminder),
                reminder.actionTarget(),
                reminder.recurringTaskId());
        store.saveReminder(updated);
        scheduler.cancel(updated.id());
        scheduler.schedule(updated, () -> triggerReminder(updated.id()));
        return ReminderResult.success(updated, "reminder updated");
    }

    public ReminderResult updateLlmTask(String id,
                                        String title,
                                        String runAtText,
                                        String instruction,
                                        String originalRequest,
                                        List<String> expectedToolCategories) {
        Optional<AutomationStore.Reminder> existing = store.findReminder(id);
        if (existing.isEmpty()) {
            return ReminderResult.failure("reminder not found: " + id);
        }
        AutomationStore.Reminder reminder = existing.get();
        if (reminder.status() != AutomationStore.ReminderStatus.PENDING) {
            return ReminderResult.failure("reminder cannot be updated because it is " + reminder.status());
        }
        if (effectiveTaskKind(reminder) != AutomationStore.AutomationTaskKind.LLM_TASK) {
            return ReminderResult.failure("target reminder is not an LLM_TASK");
        }

        Instant remindAt = reminder.remindAt();
        if (runAtText != null && !runAtText.isBlank()) {
            try {
                remindAt = parseInstant(runAtText);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                return ReminderResult.failure("runAt must be an ISO datetime, for example 2026-07-22T20:00:00+08:00");
            }
            if (!remindAt.isAfter(clock.instant())) {
                return ReminderResult.failure("runAt must be in the future");
            }
        }

        AutomationStore.Reminder updated = new AutomationStore.Reminder(
                reminder.id(),
                normalizeOptional(title, reminder.title()),
                remindAt,
                reminder.message(),
                reminder.status(),
                reminder.createdAt(),
                clock.instant(),
                null,
                reminder.sendAttempts(),
                effectiveActionType(reminder),
                reminder.actionTarget(),
                reminder.recurringTaskId(),
                AutomationStore.AutomationTaskKind.LLM_TASK,
                normalizeOptional(instruction, reminder.instruction()),
                normalizeOptional(originalRequest, reminder.originalRequest()),
                expectedToolCategories != null ? expectedToolCategories : reminder.expectedToolCategories(),
                effectiveMaxRetries(reminder));
        store.saveReminder(updated);
        scheduler.cancel(updated.id());
        scheduler.schedule(updated, () -> triggerReminder(updated.id()));
        return ReminderResult.success(updated, "llm task updated");
    }

    private ReminderResult createActionReminder(String title,
                                                String remindAtText,
                                                String message,
                                                AutomationStore.AutomationActionType actionType,
                                                String actionTarget) {
        return createTaskReminder(title, remindAtText, message, AutomationStore.AutomationTaskKind.TEXT_REMINDER,
                null, null, List.of(), 0, actionType, actionTarget);
    }

    private ReminderResult createTaskReminder(String title,
                                              String remindAtText,
                                              String message,
                                              AutomationStore.AutomationTaskKind taskKind,
                                              String instruction,
                                              String originalRequest,
                                              List<String> expectedToolCategories,
                                              int maxRetries) {
        return createTaskReminder(title, remindAtText, message, taskKind, instruction, originalRequest,
                expectedToolCategories, maxRetries, AutomationStore.AutomationActionType.TEXT, null);
    }

    private ReminderResult createTaskReminder(String title,
                                              String remindAtText,
                                              String message,
                                              AutomationStore.AutomationTaskKind taskKind,
                                              String instruction,
                                              String originalRequest,
                                              List<String> expectedToolCategories,
                                              int maxRetries,
                                              AutomationStore.AutomationActionType actionType,
                                              String actionTarget) {
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
                actionType,
                actionTarget,
                null,
                taskKind,
                instruction,
                originalRequest,
                expectedToolCategories,
                maxRetries);
        store.saveReminder(reminder);
        scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
        return ReminderResult.success(reminder,
                taskKind == AutomationStore.AutomationTaskKind.LLM_TASK ? "llm task created" : "reminder created");
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
        return createRecurringActionReminder(title, scheduleType, scheduleExpression, message,
                AutomationStore.AutomationActionType.TEXT, null);
    }

    public RecurringTaskResult createRecurringWeatherReminder(String title,
                                                              AutomationStore.RecurringScheduleType scheduleType,
                                                              String scheduleExpression,
                                                              String location,
                                                              String weatherMode,
                                                              String message) {
        AutomationStore.AutomationActionType actionType = parseWeatherActionType(weatherMode);
        if (actionType == null) {
            return RecurringTaskResult.failure("weatherMode must be CURRENT or FORECAST");
        }
        String normalizedLocation = normalizeRequired(location, "location");
        if (normalizedLocation == null) {
            return RecurringTaskResult.failure("location is required");
        }
        return createRecurringActionReminder(title, scheduleType, scheduleExpression, message, actionType, normalizedLocation);
    }

    public RecurringTaskResult createRecurringLlmTask(String title,
                                                      AutomationStore.RecurringScheduleType scheduleType,
                                                      String scheduleExpression,
                                                      String instruction,
                                                      String originalRequest,
                                                      List<String> expectedToolCategories) {
        String normalizedInstruction = normalizeRequired(instruction, "instruction");
        if (normalizedInstruction == null) {
            return RecurringTaskResult.failure("instruction is required");
        }
        return createRecurringTask(
                title,
                scheduleType,
                scheduleExpression,
                title,
                AutomationStore.AutomationActionType.TEXT,
                null,
                AutomationStore.AutomationTaskKind.LLM_TASK,
                normalizedInstruction,
                normalizeOptional(originalRequest, normalizedInstruction),
                expectedToolCategories,
                2);
    }

    private RecurringTaskResult createRecurringActionReminder(String title,
                                                             AutomationStore.RecurringScheduleType scheduleType,
                                                             String scheduleExpression,
                                                             String message,
                                                             AutomationStore.AutomationActionType actionType,
                                                             String actionTarget) {
        return createRecurringTask(title, scheduleType, scheduleExpression, message, actionType, actionTarget,
                AutomationStore.AutomationTaskKind.TEXT_REMINDER, null, null, List.of(), 0);
    }

    private RecurringTaskResult createRecurringTask(String title,
                                                    AutomationStore.RecurringScheduleType scheduleType,
                                                    String scheduleExpression,
                                                    String message,
                                                    AutomationStore.AutomationActionType actionType,
                                                    String actionTarget,
                                                    AutomationStore.AutomationTaskKind taskKind,
                                                    String instruction,
                                                    String originalRequest,
                                                    List<String> expectedToolCategories,
                                                    int maxRetries) {
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
                null,
                actionType,
                actionTarget,
                taskKind,
                instruction,
                originalRequest,
                expectedToolCategories,
                maxRetries);
        store.saveRecurringTask(task);
        scheduleRecurringInstance(task);
        return RecurringTaskResult.success(task,
                taskKind == AutomationStore.AutomationTaskKind.LLM_TASK
                        ? "recurring llm task created"
                        : "recurring reminder created");
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

        if (effectiveTaskKind(triggering) == AutomationStore.AutomationTaskKind.LLM_TASK) {
            triggerLlmTask(triggering, recipientId);
            return;
        }

        int attempts = Math.max(1, properties.getMaxSendAttempts());
        Exception lastError = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                dispatcher.send(recipientId, formatTriggeredMessage(triggering));
                store.saveReminder(copyReminder(triggering, AutomationStore.ReminderStatus.SENT, null, i));
                advanceRecurringTaskIfNeeded(triggering);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("reminder dispatch failed: id={}, attempt={}/{}", reminderId, i, attempts, e);
                // context token 过期，等几秒重试没有意义，token 只能在用户发消息时刷新
                if (isContextTokenError(e)) {
                    break;
                }
                if (i < attempts) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (isContextTokenError(lastError)) {
            // token 问题不属于系统故障，保持 PENDING 等用户下次发消息时自然重试
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.PENDING,
                    "waiting for context refresh: " + lastError.getMessage(),
                    0));
            log.info("reminder {} kept PENDING, will retry on next user message", reminderId);
        } else {
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.FAILED,
                    lastError != null ? lastError.getMessage() : "send failed",
                    attempts));
        }
    }

    private void triggerLlmTask(AutomationStore.Reminder reminder, String recipientId) {
        if (scheduledTaskExecutor == null) {
            failTriggeredLlmTask(reminder, recipientId, "scheduled task executor is not available", 0);
            return;
        }
        String instruction = normalizeRequired(reminder.instruction(), "instruction");
        if (instruction == null) {
            failTriggeredLlmTask(reminder, recipientId, "scheduled task instruction is required", 0);
            return;
        }

        int retries = effectiveMaxRetries(reminder);
        int attempts = retries + 1;
        String lastError = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                ScheduledTaskExecutionResult result = scheduledTaskExecutor.execute(new ScheduledTaskExecutionRequest(
                        reminder.id(),
                        recipientId,
                        reminder.title(),
                        instruction,
                        reminder.originalRequest(),
                        reminder.expectedToolCategories(),
                        reminder.remindAt(),
                        reminder.recurringTaskId() != null && !reminder.recurringTaskId().isBlank()));
                if (result != null && result.success()) {
                    String message = normalizeOptional(result.message(), "scheduled task completed");
                    dispatcher.send(recipientId, message);
                    store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.SENT, null, i));
                    advanceRecurringTaskIfNeeded(reminder);
                    return;
                }
                lastError = result != null && result.errorMessage() != null
                        ? result.errorMessage()
                        : "scheduled task failed";
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("scheduled task execution failed: id={}, attempt={}/{}", reminder.id(), i, attempts, e);
            }
        }
        failTriggeredLlmTask(reminder, recipientId, lastError, attempts);
    }

    private void failTriggeredLlmTask(AutomationStore.Reminder reminder,
                                      String recipientId,
                                      String failureMessage,
                                      int attempts) {
        String reason = failureMessage != null && !failureMessage.isBlank()
                ? failureMessage
                : "scheduled task failed";
        store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.FAILED, reason, attempts));
        pauseRecurringTaskIfNeeded(reminder, reason);
        try {
            dispatcher.send(recipientId, "计划任务执行失败：" + reason);
        } catch (Exception e) {
            log.warn("failed to send scheduled task failure notice: id={}", reminder.id(), e);
        }
    }

    private static boolean isContextTokenError(Exception e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("context token") || msg.contains("contextToken"));
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
                effectiveActionType(reminder),
                reminder.actionTarget(),
                reminder.recurringTaskId(),
                effectiveTaskKind(reminder),
                reminder.instruction(),
                reminder.originalRequest(),
                reminder.expectedToolCategories(),
                effectiveMaxRetries(reminder));
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
                effectiveActionType(task),
                task.actionTarget(),
                task.id(),
                effectiveTaskKind(task),
                task.instruction(),
                task.originalRequest(),
                task.expectedToolCategories(),
                effectiveMaxRetries(task));
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
                failureMessage,
                effectiveActionType(task),
                task.actionTarget(),
                effectiveTaskKind(task),
                task.instruction(),
                task.originalRequest(),
                task.expectedToolCategories(),
                effectiveMaxRetries(task));
    }

    private void pauseRecurringTaskIfNeeded(AutomationStore.Reminder reminder, String failureMessage) {
        if (reminder.recurringTaskId() == null || reminder.recurringTaskId().isBlank()) {
            return;
        }
        Optional<AutomationStore.RecurringTask> existing = store.findRecurringTask(reminder.recurringTaskId());
        if (existing.isEmpty()) {
            return;
        }
        AutomationStore.RecurringTask task = existing.get();
        AutomationStore.RecurringTask paused = copyRecurringTask(
                task,
                task.nextRunAt(),
                AutomationStore.RecurringTaskStatus.PAUSED,
                failureMessage);
        store.saveRecurringTask(paused);
        for (AutomationStore.Reminder pending : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            if (task.id().equals(pending.recurringTaskId())) {
                deleteReminder(pending.id());
            }
        }
    }

    private static AutomationStore.ScheduleItemStatus effectiveScheduleStatus(AutomationStore.ScheduleItem item) {
        return item.status() != null ? item.status() : AutomationStore.ScheduleItemStatus.ACTIVE;
    }

    private String formatTriggeredMessage(AutomationStore.Reminder reminder) {
        return switch (effectiveActionType(reminder)) {
            case TEXT -> formatReminderMessage(reminder);
            case WEATHER_CURRENT -> formatActionMessage(reminder, executeWeatherAction(reminder, false));
            case WEATHER_FORECAST -> formatActionMessage(reminder, executeWeatherAction(reminder, true));
        };
    }

    private String executeWeatherAction(AutomationStore.Reminder reminder, boolean forecast) {
        if (weatherTools == null) {
            throw new IllegalStateException("weather tool is not available");
        }
        String location = normalizeRequired(reminder.actionTarget(), "location");
        if (location == null) {
            throw new IllegalStateException("weather location is required");
        }
        return forecast
                ? weatherTools.getWeatherForecast(location)
                : weatherTools.getCurrentWeather(location);
    }

    private String formatActionMessage(AutomationStore.Reminder reminder, String actionResult) {
        String prefix = normalizeOptional(reminder.message(), reminder.title());
        return prefix + "\n" + actionResult;
    }

    private static AutomationStore.AutomationActionType effectiveActionType(AutomationStore.Reminder reminder) {
        return reminder.actionType() != null ? reminder.actionType() : AutomationStore.AutomationActionType.TEXT;
    }

    private static AutomationStore.AutomationActionType effectiveActionType(AutomationStore.RecurringTask task) {
        return task.actionType() != null ? task.actionType() : AutomationStore.AutomationActionType.TEXT;
    }

    private static AutomationStore.AutomationTaskKind effectiveTaskKind(AutomationStore.Reminder reminder) {
        return reminder.taskKind() != null
                ? reminder.taskKind()
                : AutomationStore.AutomationTaskKind.TEXT_REMINDER;
    }

    private static AutomationStore.AutomationTaskKind effectiveTaskKind(AutomationStore.RecurringTask task) {
        return task.taskKind() != null
                ? task.taskKind()
                : AutomationStore.AutomationTaskKind.TEXT_REMINDER;
    }

    private static int effectiveMaxRetries(AutomationStore.Reminder reminder) {
        return reminder.maxRetries() > 0 ? reminder.maxRetries() : 2;
    }

    private static int effectiveMaxRetries(AutomationStore.RecurringTask task) {
        return task.maxRetries() > 0 ? task.maxRetries() : 2;
    }

    private static AutomationStore.AutomationActionType parseWeatherActionType(String weatherMode) {
        if (weatherMode == null || weatherMode.isBlank()) {
            return AutomationStore.AutomationActionType.WEATHER_FORECAST;
        }
        return switch (weatherMode.trim().toUpperCase(Locale.ROOT)) {
            case "CURRENT", "BASE", "NOW" -> AutomationStore.AutomationActionType.WEATHER_CURRENT;
            case "FORECAST", "ALL" -> AutomationStore.AutomationActionType.WEATHER_FORECAST;
            default -> null;
        };
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
