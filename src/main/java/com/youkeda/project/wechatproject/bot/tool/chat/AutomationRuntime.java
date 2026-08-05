package com.youkeda.project.wechatproject.bot.tool.chat;

import com.youkeda.project.wechatproject.bot.tool.travel.WeatherTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
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
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class AutomationRuntime implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuntime.class);
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final String HEARTBEAT_SOURCE_DEFAULT = "DEFAULT";
    private static final String HEARTBEAT_SOURCE_AGENT = "AGENT";
    private static final String HEARTBEAT_SOURCE_USER_ACTIVITY = "USER_ACTIVITY";

    private final AutomationStore store;
    private final ReminderScheduler scheduler;
    private final ReminderDispatcher dispatcher;
    private final AutomationProperties properties;
    private final Clock clock;
    private final ZoneId zoneId;
    private final WeatherTools weatherTools;
    private final ScheduledTaskExecutor scheduledTaskExecutor;
    private final Set<String> knownHeartbeatUsers = ConcurrentHashMap.newKeySet();

    public static void setCurrentUser(String userId) {
        if (userId == null || userId.isBlank()) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(userId);
        }
    }

    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    static String currentUserId() {
        return CURRENT_USER.get();
    }

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
        recoverTriggeringReminders();
        reschedulePendingReminders();
        scheduleRecurringTasks();
        if (properties.isHeartbeatEnabled()) {
            seedConfiguredHeartbeatUser();
            scheduler.scheduleWatchdog(
                    Duration.ofMinutes(Math.max(1, properties.getHeartbeatWatchdogMinutes())),
                    this::runHeartbeatWatchdog);
        }
    }

    public void markUserActivity(String userId) {
        if (!properties.isHeartbeatEnabled() || userId == null || userId.isBlank()) {
            return;
        }
        String normalized = userId.trim();
        knownHeartbeatUsers.add(normalized);
        rebaseHeartbeatAfterUserActivity(normalized);
    }

    public HeartbeatResult scheduleAgentWake(String userId, String wakeAtText, String reason) {
        if (!properties.isHeartbeatEnabled()) {
            return HeartbeatResult.failure("agent heartbeat is disabled");
        }
        if (userId == null || userId.isBlank()) {
            return HeartbeatResult.failure("user id is required");
        }
        Instant requested;
        try {
            requested = parseInstant(wakeAtText);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return HeartbeatResult.failure("wakeAt must be an ISO datetime with timezone");
        }
        String normalizedUser = userId.trim();
        knownHeartbeatUsers.add(normalizedUser);
        Instant wakeAt = clampHeartbeatWakeAt(requested);
        AutomationStore.Reminder heartbeat = upsertHeartbeat(
                normalizedUser, wakeAt, HEARTBEAT_SOURCE_AGENT,
                normalizeOptional(reason, "agent selected wake time"), true);
        log.info("[HEARTBEAT] agent wake scheduled userId={} heartbeatId={} nextWakeAt={} reason={}",
                normalizedUser, heartbeat.id(), heartbeat.remindAt(),
                normalizeOptional(reason, "agent selected wake time"));
        return HeartbeatResult.success(heartbeat.remindAt(), heartbeat.id());
    }

    public HeartbeatResult scheduleWakeNoLaterThan(String userId, Instant requested, String reason) {
        if (!properties.isHeartbeatEnabled()) {
            return HeartbeatResult.failure("agent heartbeat is disabled");
        }
        if (userId == null || userId.isBlank() || requested == null) {
            return HeartbeatResult.failure("user id and wake time are required");
        }
        String normalizedUser = userId.trim();
        knownHeartbeatUsers.add(normalizedUser);
        Instant wakeAt = clampHeartbeatWakeAt(requested);
        AutomationStore.Reminder heartbeat = upsertHeartbeat(
                normalizedUser, wakeAt, HEARTBEAT_SOURCE_AGENT,
                normalizeOptional(reason, "system requested earlier agent wake"), false);
        log.info("[HEARTBEAT] shared wake bounded userId={} heartbeatId={} requestedAt={} "
                        + "nextWakeAt={} reason={}",
                normalizedUser, heartbeat.id(), wakeAt, heartbeat.remindAt(),
                normalizeOptional(reason, "system requested earlier agent wake"));
        return HeartbeatResult.success(heartbeat.remindAt(), heartbeat.id());
    }

    public Optional<AutomationStore.Reminder> findAgentHeartbeat(String userId) {
        return activeHeartbeatFor(userId);
    }

    private void recoverTriggeringReminders() {
        Instant now = clock.instant();
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.TRIGGERING)) {
            store.transitionReminderStatus(reminder.id(), AutomationStore.ReminderStatus.TRIGGERING,
                    AutomationStore.ReminderStatus.PENDING, now);
        }
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
        if (resolveRecipientIdForOwner(currentUserId()) == null) {
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
                null)
                .withOwnerId(currentUserId());
        store.saveReminder(reminder);
        scheduleReminder(reminder);
        log.info("TEXT_REMINDER created successfully: id={}, title={}, remindAt={}",
                reminder.id(), title, remindAt);
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
                reminder.recurringTaskId())
                .withOwnerId(reminder.ownerId());
        store.saveReminder(updated);
        scheduler.cancel(updated.id());
        scheduleReminder(updated);
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
                effectiveMaxRetries(reminder),
                reminder.ownerId());
        store.saveReminder(updated);
        scheduler.cancel(updated.id());
        scheduleReminder(updated);
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
        if (resolveRecipientIdForOwner(currentUserId()) == null) {
            return ReminderResult.failure("reminder recipient is not bound yet");
        }

        Optional<AutomationStore.Reminder> duplicate = findRecentDuplicate(
                normalizedTitle, remindAt, normalizedMessage, now);
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
                actionType,
                actionTarget,
                null,
                taskKind,
                instruction,
                originalRequest,
                expectedToolCategories,
                maxRetries)
                .withOwnerId(currentUserId());
        store.saveReminder(reminder);
        scheduleReminder(reminder);
        String kindLabel = taskKind == AutomationStore.AutomationTaskKind.LLM_TASK ? "LLM_TASK" : "TEXT_REMINDER";
        log.info("{} created successfully: id={}, title={}, remindAt={}",
                kindLabel, reminder.id(), title, remindAt);
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
        if (resolveRecipientIdForOwner(currentUserId()) == null) {
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
                maxRetries)
                .withOwnerId(currentUserId());
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
        Optional<AutomationStore.Reminder> claimed = store.transitionReminderStatus(
                reminderId, AutomationStore.ReminderStatus.PENDING,
                AutomationStore.ReminderStatus.TRIGGERING, clock.instant());
        if (claimed.isEmpty()) {
            return;
        }
        AutomationStore.Reminder triggering = claimed.get();
        if (isHeartbeat(triggering)) {
            log.info("[HEARTBEAT] wake claimed heartbeatId={} userId={} scheduledFor={} source={}",
                    triggering.id(), triggering.ownerId(), triggering.remindAt(),
                    triggering.actionTarget());
        }

        String recipientId = resolveRecipientIdForOwner(triggering.ownerId());
        if (recipientId == null) {
            store.saveReminder(copyReminder(
                    triggering,
                    AutomationStore.ReminderStatus.FAILED,
                    "reminder recipient is not bound",
                    triggering.sendAttempts()));
            return;
        }

        if (effectiveTaskKind(triggering) == AutomationStore.AutomationTaskKind.LLM_TASK
                || effectiveTaskKind(triggering) == AutomationStore.AutomationTaskKind.AGENT_HEARTBEAT) {
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

        // 检查是否有之前执行成功但 context token 缺失导致发送失败的结果
        String stashedResult = extractStashedResult(reminder);
        if (stashedResult != null) {
            store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.PENDING, null, 0));
            try {
                dispatcher.send(recipientId, stashedResult);
                store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.SENT, null, 1));
                advanceRecurringTaskIfNeeded(reminder);
                log.info("LLM_TASK {} stashed result sent successfully", reminder.id());
                return;
            } catch (Exception e) {
                if (isContextTokenError(e)) {
                    store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.PENDING,
                            "EXECUTED_OK:" + stashedResult, 0));
                    log.info("LLM_TASK {} resend failed (context token), kept PENDING", reminder.id());
                } else {
                    failTriggeredLlmTask(reminder, recipientId, e.getMessage(), 1);
                }
                return;
            }
        }

        for (int i = 1; i <= attempts; i++) {
            try {
                ScheduledTaskExecutionResult result = scheduledTaskExecutor.execute(new ScheduledTaskExecutionRequest(
                        reminder.id(),
                        recipientId,
                        reminder.title(),
                        instruction,
                        isHeartbeat(reminder) ? reminder.message() : reminder.originalRequest(),
                        reminder.expectedToolCategories(),
                        reminder.remindAt(),
                        reminder.recurringTaskId() != null && !reminder.recurringTaskId().isBlank(),
                        effectiveTaskKind(reminder)));
                if (result != null && result.success()) {
                    if (isHeartbeat(reminder) && result.nextWakeAt() != null) {
                        AutomationStore.Reminder latest = store.findReminder(reminder.id())
                                .orElse(reminder);
                        boolean concurrentAgentWake = latest.status() == AutomationStore.ReminderStatus.TRIGGERING
                                && (!latest.remindAt().equals(reminder.remindAt())
                                || !Objects.equals(latest.message(), reminder.message()));
                        Instant nextWakeAt = concurrentAgentWake
                                ? latest.remindAt() : clampHeartbeatWakeAt(result.nextWakeAt());
                        String nextWakeNote = concurrentAgentWake
                                ? latest.message()
                                : normalizeOptional(result.nextWakeNote(), reminder.message());
                        String nextWakeSource = concurrentAgentWake
                                ? latest.actionTarget() : HEARTBEAT_SOURCE_AGENT;
                        AutomationStore.Reminder pending = heartbeatCopy(
                                latest,
                                nextWakeAt,
                                AutomationStore.ReminderStatus.PENDING,
                                nextWakeNote,
                                nextWakeSource,
                                null);
                        store.saveReminder(pending);
                        scheduleReminder(pending);
                        log.info("[HEARTBEAT] decision persisted heartbeatId={} userId={} "
                                        + "nextWakeAt={} proactive={} note={}",
                                pending.id(), pending.ownerId(), pending.remindAt(),
                                result.message() != null && !result.message().isBlank(),
                                pending.message());
                        String proactiveMessage = normalizeRequired(result.message(), "message");
                        if (proactiveMessage != null && !result.alreadyDispatched()) {
                            try {
                                dispatcher.send(recipientId, proactiveMessage);
                                log.info("[HEARTBEAT] proactive message dispatched heartbeatId={} userId={}",
                                        reminder.id(), recipientId);
                            } catch (Exception sendError) {
                                log.warn("heartbeat proactive message could not be delivered: id={}, error={}",
                                        reminder.id(), sendError.getMessage());
                            }
                        }
                        return;
                    }
                    String message = normalizeOptional(result.message(), "scheduled task completed");
                    if (result.alreadyDispatched()) {
                        store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.SENT, null, i));
                        advanceRecurringTaskIfNeeded(reminder);
                        return;
                    }
                    try {
                        dispatcher.send(recipientId, message);
                        store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.SENT, null, i));
                        advanceRecurringTaskIfNeeded(reminder);
                        return;
                    } catch (Exception sendEx) {
                        if (isContextTokenError(sendEx)) {
                            log.info("LLM_TASK {} executed OK but send failed (context token), result stashed",
                                    reminder.id());
                            store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.PENDING,
                                    "EXECUTED_OK:" + message, 0));
                            return;
                        }
                        throw sendEx;
                    }
                }
                lastError = result != null && result.errorMessage() != null
                        ? result.errorMessage()
                        : "scheduled task failed";
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("scheduled task execution failed: id={}, attempt={}/{}", reminder.id(), i, attempts, e);
                if (isContextTokenError(e)) {
                    break;
                }
            }
        }

        if (isHeartbeat(reminder)) {
            AutomationStore.Reminder fallback = heartbeatCopy(
                    reminder,
                    fallbackHeartbeatWakeAt(),
                    AutomationStore.ReminderStatus.PENDING,
                    reminder.message(),
                    HEARTBEAT_SOURCE_DEFAULT,
                    lastError);
            store.saveReminder(fallback);
            scheduleReminder(fallback);
            log.warn("[HEARTBEAT] execution failed heartbeatId={} fallbackWakeAt={} error={}",
                    reminder.id(), fallback.remindAt(), lastError);
        } else if (isContextTokenErrorFromMessage(lastError)) {
            store.saveReminder(copyReminder(reminder, AutomationStore.ReminderStatus.PENDING,
                    "waiting for context refresh: " + lastError, 0));
            log.info("LLM_TASK {} kept PENDING, will retry on next user message", reminder.id());
        } else {
            failTriggeredLlmTask(reminder, recipientId, lastError, attempts);
        }
    }

    private static String extractStashedResult(AutomationStore.Reminder reminder) {
        String msg = reminder.failureMessage();
        if (msg != null && msg.startsWith("EXECUTED_OK:")) {
            return msg.substring("EXECUTED_OK:".length());
        }
        return null;
    }

    private static boolean isContextTokenErrorFromMessage(String message) {
        return message != null && (message.contains("context token") || message.contains("contextToken"));
    }

    private void failTriggeredLlmTask(AutomationStore.Reminder reminder,
                                      String recipientId,
                                      String failureMessage,
                                      int attempts) {
        String reason = failureMessage != null && !failureMessage.isBlank()
                ? failureMessage
                : "scheduled task failed";
        if (isHeartbeat(reminder)) {
            AutomationStore.Reminder fallback = heartbeatCopy(
                    reminder, fallbackHeartbeatWakeAt(), AutomationStore.ReminderStatus.PENDING,
                    reminder.message(), HEARTBEAT_SOURCE_DEFAULT, reason);
            store.saveReminder(fallback);
            scheduleReminder(fallback);
            return;
        }
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
        Instant now = clock.instant();
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            String recipientId = resolveRecipientIdForOwner(reminder.ownerId());
            if (!triggeredByUserId.equals(recipientId)) {
                continue;
            }
            if (!reminder.remindAt().isAfter(now)) {
                log.info("retrying overdue pending reminder: id={}, title={}", reminder.id(), reminder.title());
                triggerReminder(reminder.id());
            }
        }
    }

    void runHeartbeatWatchdog() {
        if (!properties.isHeartbeatEnabled()) {
            return;
        }
        store.listReminders(null).stream()
                .filter(this::isHeartbeat)
                .map(AutomationStore.Reminder::ownerId)
                .filter(owner -> owner != null && !owner.isBlank())
                .forEach(knownHeartbeatUsers::add);
        seedConfiguredHeartbeatUser();

        Instant now = clock.instant();
        log.info("[HEARTBEAT] watchdog check started users={} at={}",
                knownHeartbeatUsers.size(), now);
        for (String userId : List.copyOf(knownHeartbeatUsers)) {
            Optional<AutomationStore.Reminder> active = activeHeartbeatFor(userId);
            if (active.isEmpty()) {
                log.info("[HEARTBEAT] watchdog waking userId={} reason=missing-next-wake", userId);
                AutomationStore.Reminder heartbeat = upsertHeartbeat(
                        userId, now.plusSeconds(1), HEARTBEAT_SOURCE_DEFAULT,
                        "hourly watchdog repaired missing wake time", true);
                triggerReminder(heartbeat.id());
                continue;
            }
            AutomationStore.Reminder heartbeat = active.get();
            if (heartbeat.status() == AutomationStore.ReminderStatus.PENDING
                    && !heartbeat.remindAt().isAfter(now)) {
                log.info("[HEARTBEAT] watchdog waking userId={} heartbeatId={} reason=due "
                                + "scheduledFor={}",
                        userId, heartbeat.id(), heartbeat.remindAt());
                triggerReminder(heartbeat.id());
                continue;
            }
            if (heartbeat.status() == AutomationStore.ReminderStatus.TRIGGERING
                    && heartbeat.updatedAt().plus(Duration.ofMinutes(
                            Math.max(5, properties.getHeartbeatBusyDeferralMinutes() * 2L))).isBefore(now)) {
                log.warn("[HEARTBEAT] watchdog recovering stuck wake userId={} heartbeatId={} updatedAt={}",
                        userId, heartbeat.id(), heartbeat.updatedAt());
                store.transitionReminderStatus(heartbeat.id(), AutomationStore.ReminderStatus.TRIGGERING,
                        AutomationStore.ReminderStatus.PENDING, now);
                triggerReminder(heartbeat.id());
                continue;
            }
            log.info("[HEARTBEAT] watchdog skipped userId={} heartbeatId={} status={} "
                            + "nextWakeAt={} source={}",
                    userId, heartbeat.id(), heartbeat.status(), heartbeat.remindAt(),
                    heartbeat.actionTarget());
        }
    }

    private void seedConfiguredHeartbeatUser() {
        String configured = properties.getDefaultRecipientId();
        if (configured != null && !configured.isBlank()) {
            registerHeartbeatUser(configured.trim());
            return;
        }
        store.getRecipientBinding().map(AutomationStore.RecipientBinding::recipientId)
                .filter(value -> value != null && !value.isBlank())
                .ifPresent(this::registerHeartbeatUser);
    }

    private void registerHeartbeatUser(String userId) {
        String normalized = userId.trim();
        knownHeartbeatUsers.add(normalized);
        ensureDefaultHeartbeat(normalized);
    }

    private synchronized AutomationStore.Reminder rebaseHeartbeatAfterUserActivity(String userId) {
        Instant nextWakeAt = clampHeartbeatWakeAt(clock.instant().plus(Duration.ofMinutes(
                Math.max(1, properties.getHeartbeatFallbackMinutes()))));
        Optional<AutomationStore.Reminder> active = activeHeartbeatFor(userId);
        if (active.isEmpty()) {
            return upsertHeartbeat(userId, nextWakeAt, HEARTBEAT_SOURCE_USER_ACTIVITY,
                    "user activity changed heartbeat context", true);
        }

        AutomationStore.Reminder current = active.get();
        if (current.status() != AutomationStore.ReminderStatus.TRIGGERING
                && HEARTBEAT_SOURCE_AGENT.equals(current.actionTarget())
                && !current.remindAt().isAfter(nextWakeAt)) {
            log.info("[HEARTBEAT] user activity kept earlier agent wake userId={} heartbeatId={} "
                            + "nextWakeAt={} source={}",
                    userId, current.id(), current.remindAt(), current.actionTarget());
            return current;
        }

        Instant previousWakeAt = current.remindAt();
        AutomationStore.ReminderStatus status = current.status() == AutomationStore.ReminderStatus.TRIGGERING
                ? AutomationStore.ReminderStatus.TRIGGERING : AutomationStore.ReminderStatus.PENDING;
        AutomationStore.Reminder updated = heartbeatCopy(
                current, nextWakeAt, status,
                "user activity changed heartbeat context", HEARTBEAT_SOURCE_USER_ACTIVITY, null);
        store.saveReminder(updated);
        if (status == AutomationStore.ReminderStatus.PENDING) {
            scheduler.cancel(updated.id());
            scheduleReminder(updated);
        }
        log.info("[HEARTBEAT] wake rebased by user activity userId={} heartbeatId={} "
                        + "previousWakeAt={} nextWakeAt={} source={}",
                userId, updated.id(), previousWakeAt, updated.remindAt(), updated.actionTarget());
        return updated;
    }

    private AutomationStore.Reminder ensureDefaultHeartbeat(String userId) {
        Optional<AutomationStore.Reminder> existing = activeHeartbeatFor(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant fallback = clock.instant().plus(Duration.ofMinutes(
                Math.max(1, properties.getHeartbeatFallbackMinutes())));
        return upsertHeartbeat(userId, fallback, HEARTBEAT_SOURCE_DEFAULT,
                "default heartbeat", true);
    }

    private synchronized AutomationStore.Reminder upsertHeartbeat(String userId,
                                                                   Instant wakeAt,
                                                                   String source,
                                                                   String reason,
                                                                   boolean replaceExisting) {
        Optional<AutomationStore.Reminder> active = activeHeartbeatFor(userId);
        if (active.isPresent()) {
            AutomationStore.Reminder current = active.get();
            if (current.status() == AutomationStore.ReminderStatus.TRIGGERING) {
                AutomationStore.Reminder updated = heartbeatCopy(
                        current, wakeAt, AutomationStore.ReminderStatus.TRIGGERING,
                        reason, source, null);
                store.saveReminder(updated);
                return updated;
            }
            Instant effectiveWakeAt = replaceExisting
                    ? wakeAt : (wakeAt.isBefore(current.remindAt()) ? wakeAt : current.remindAt());
            String effectiveSource = replaceExisting || effectiveWakeAt.equals(wakeAt)
                    ? source : current.actionTarget();
            String effectiveReason = effectiveWakeAt.equals(wakeAt) ? reason : current.message();
            AutomationStore.Reminder updated = heartbeatCopy(
                    current, effectiveWakeAt, AutomationStore.ReminderStatus.PENDING,
                    effectiveReason, effectiveSource, null);
            store.saveReminder(updated);
            scheduler.cancel(updated.id());
            scheduleReminder(updated);
            log.info("[HEARTBEAT] wake updated userId={} heartbeatId={} nextWakeAt={} source={}",
                    userId, updated.id(), updated.remindAt(), updated.actionTarget());
            return updated;
        }

        Instant now = clock.instant();
        AutomationStore.Reminder heartbeat = new AutomationStore.Reminder(
                newReminderId(),
                "agent heartbeat",
                wakeAt,
                normalizeOptional(reason, "agent heartbeat"),
                AutomationStore.ReminderStatus.PENDING,
                now,
                now,
                null,
                0,
                AutomationStore.AutomationActionType.TEXT,
                source,
                null,
                AutomationStore.AutomationTaskKind.AGENT_HEARTBEAT,
                "Evaluate whether to contact the user or resume a sleeping task, then choose the next wake time.",
                null,
                List.of(),
                0,
                userId);
        store.saveReminder(heartbeat);
        scheduleReminder(heartbeat);
        log.info("[HEARTBEAT] wake created userId={} heartbeatId={} nextWakeAt={} source={}",
                userId, heartbeat.id(), heartbeat.remindAt(), heartbeat.actionTarget());
        return heartbeat;
    }

    private Optional<AutomationStore.Reminder> activeHeartbeatFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return store.listReminders(null).stream()
                .filter(this::isHeartbeat)
                .filter(reminder -> userId.equals(reminder.ownerId()))
                .filter(reminder -> reminder.status() == AutomationStore.ReminderStatus.PENDING
                        || reminder.status() == AutomationStore.ReminderStatus.TRIGGERING)
                .min(java.util.Comparator.comparing(AutomationStore.Reminder::remindAt));
    }

    private boolean isHeartbeat(AutomationStore.Reminder reminder) {
        return effectiveTaskKind(reminder) == AutomationStore.AutomationTaskKind.AGENT_HEARTBEAT;
    }

    private Instant clampHeartbeatWakeAt(Instant requested) {
        Instant now = clock.instant();
        Instant minimum = now.plus(Duration.ofMinutes(
                Math.max(1, properties.getHeartbeatMinIntervalMinutes())));
        Instant maximum = now.plus(Duration.ofHours(
                Math.max(1, properties.getHeartbeatMaxIntervalHours())));
        if (requested == null || requested.isBefore(minimum)) {
            return minimum;
        }
        return requested.isAfter(maximum) ? maximum : requested;
    }

    private Instant fallbackHeartbeatWakeAt() {
        return clampHeartbeatWakeAt(clock.instant().plus(Duration.ofMinutes(
                Math.max(1, properties.getHeartbeatFallbackMinutes()))));
    }

    private void reschedulePendingReminders() {
        Instant now = clock.instant();
        for (AutomationStore.Reminder reminder : store.listReminders(AutomationStore.ReminderStatus.PENDING)) {
            if (reminder.remindAt().isAfter(now)) {
                scheduleReminder(reminder);
            } else if (isHeartbeat(reminder)) {
                AutomationStore.Reminder recovered = heartbeatCopy(
                        reminder, now.plusSeconds(5), AutomationStore.ReminderStatus.PENDING,
                        reminder.message(), reminder.actionTarget(), "recovered overdue heartbeat on startup");
                store.saveReminder(recovered);
                scheduleReminder(recovered);
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
        String ownerId = currentUserId();
        return store.listReminders(AutomationStore.ReminderStatus.PENDING).stream()
                .filter(reminder -> reminder.title().equals(title))
                .filter(reminder -> reminder.remindAt().equals(remindAt))
                .filter(reminder -> reminder.message().equals(message))
                .filter(reminder -> java.util.Objects.equals(reminder.ownerId(), ownerId))
                .filter(reminder -> reminder.createdAt().plusSeconds(60).isAfter(now))
                .findFirst();
    }

    private String resolveRecipientIdForOwner(String ownerId) {
        if (ownerId != null && !ownerId.isBlank()) {
            return ownerId;
        }
        // Legacy ownerless reminders are safe only with an explicit static recipient.
        // A mutable "last bound user" could leak an old reminder to a different user.
        String configured = properties.getDefaultRecipientId();
        return configured != null && !configured.isBlank() ? configured.trim() : null;
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
                effectiveMaxRetries(reminder),
                reminder.ownerId());
    }

    private AutomationStore.Reminder heartbeatCopy(AutomationStore.Reminder reminder,
                                                    Instant wakeAt,
                                                    AutomationStore.ReminderStatus status,
                                                    String reason,
                                                    String source,
                                                    String failureMessage) {
        return new AutomationStore.Reminder(
                reminder.id(),
                reminder.title(),
                wakeAt,
                normalizeOptional(reason, reminder.message()),
                status,
                reminder.createdAt(),
                clock.instant(),
                failureMessage,
                0,
                AutomationStore.AutomationActionType.TEXT,
                source,
                null,
                AutomationStore.AutomationTaskKind.AGENT_HEARTBEAT,
                reminder.instruction(),
                reminder.originalRequest(),
                reminder.expectedToolCategories(),
                0,
                reminder.ownerId());
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
        return "R-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String newScheduleId() {
        return "S-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String newRecurringTaskId() {
        return "RR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
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
                effectiveMaxRetries(task))
                .withOwnerId(task.ownerId());
        store.saveReminder(reminder);
        scheduleReminder(reminder);
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
                effectiveMaxRetries(task),
                task.ownerId());
    }

    private void scheduleReminder(AutomationStore.Reminder reminder) {
        scheduler.schedule(reminder, () -> triggerReminder(reminder.id()));
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

        default void scheduleWatchdog(Duration interval, Runnable task) {
        }
    }

    public interface ReminderDispatcher {
        void send(String recipientId, String message) throws Exception;
    }

    public record ReminderResult(boolean success, AutomationStore.Reminder reminder, String message) {
        static ReminderResult success(AutomationStore.Reminder reminder, String message) {
            AutomationEvidenceContext.recordReminder(reminder);
            return new ReminderResult(true, reminder, message);
        }

        static ReminderResult failure(String message) {
            AutomationEvidenceContext.recordFailure(
                    AutomationEvidenceContext.EntityType.REMINDER, message);
            return new ReminderResult(false, null, message);
        }
    }

    public record ScheduleResult(boolean success, AutomationStore.ScheduleItem item, String message) {
        static ScheduleResult success(AutomationStore.ScheduleItem item, String message) {
            AutomationEvidenceContext.recordSchedule(item);
            return new ScheduleResult(true, item, message);
        }

        static ScheduleResult failure(String message) {
            AutomationEvidenceContext.recordFailure(
                    AutomationEvidenceContext.EntityType.SCHEDULE, message);
            return new ScheduleResult(false, null, message);
        }
    }

    public record RecurringTaskResult(boolean success, AutomationStore.RecurringTask task, String message) {
        static RecurringTaskResult success(AutomationStore.RecurringTask task, String message) {
            AutomationEvidenceContext.recordRecurring(task);
            return new RecurringTaskResult(true, task, message);
        }

        static RecurringTaskResult failure(String message) {
            AutomationEvidenceContext.recordFailure(
                    AutomationEvidenceContext.EntityType.RECURRING, message);
            return new RecurringTaskResult(false, null, message);
        }
    }

    public record HeartbeatResult(boolean success, Instant nextWakeAt, String heartbeatId, String message) {
        static HeartbeatResult success(Instant nextWakeAt, String heartbeatId) {
            return new HeartbeatResult(true, nextWakeAt, heartbeatId, "agent wake scheduled");
        }

        static HeartbeatResult failure(String message) {
            return new HeartbeatResult(false, null, null, message);
        }
    }

    public static class SpringReminderScheduler implements ReminderScheduler, org.springframework.beans.factory.DisposableBean {
        private final TaskScheduler taskScheduler;
        private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
        private final java.util.concurrent.ExecutorService executionExecutor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        private volatile ScheduledFuture<?> watchdogFuture;

        public SpringReminderScheduler(TaskScheduler taskScheduler) {
            this.taskScheduler = taskScheduler;
        }

        @Override
        public void schedule(AutomationStore.Reminder reminder, Runnable task) {
            cancel(reminder.id());
            Runnable selfCleaning = () -> {
                try {
                    executionExecutor.submit(task);
                } finally {
                    futures.remove(reminder.id());
                }
            };
            ScheduledFuture<?> future = taskScheduler.schedule(selfCleaning, reminder.remindAt());
            if (future != null) {
                futures.put(reminder.id(), future);
            }
        }

        @Override
        public boolean cancel(String reminderId) {
            ScheduledFuture<?> future = futures.remove(reminderId);
            return future != null && future.cancel(false);
        }

        @Override
        public synchronized void scheduleWatchdog(Duration interval, Runnable task) {
            if (watchdogFuture != null) {
                watchdogFuture.cancel(false);
            }
            watchdogFuture = taskScheduler.scheduleWithFixedDelay(
                    () -> executionExecutor.submit(task), interval);
        }

        @Override
        public void destroy() {
            ScheduledFuture<?> watchdog = watchdogFuture;
            if (watchdog != null) watchdog.cancel(false);
            executionExecutor.shutdownNow();
        }
    }
}
