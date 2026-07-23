package com.youkeda.project.wechatproject.bot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
                0);
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
        if (reminder.status() != AutomationStore.ReminderStatus.PENDING
                && reminder.status() != AutomationStore.ReminderStatus.TRIGGERING) {
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
                now,
                now);
        store.saveScheduleItem(item);
        return ScheduleResult.success(item, "schedule item created");
    }

    public List<AutomationStore.ScheduleItem> listScheduleItems(String fromText, String toText) {
        return store.listScheduleItems(parseInstant(fromText), parseInstant(toText));
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
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("reminder dispatch failed: id={}, attempt={}/{}", reminderId, i, attempts, e);
            }
        }
        store.saveReminder(copyReminder(
                triggering,
                AutomationStore.ReminderStatus.FAILED,
                lastError != null ? lastError.getMessage() : "send failed",
                attempts));
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
                sendAttempts);
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
