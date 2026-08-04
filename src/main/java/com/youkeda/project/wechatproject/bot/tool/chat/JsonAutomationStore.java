package com.youkeda.project.wechatproject.bot.tool.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class JsonAutomationStore implements AutomationStore {

    private static final Logger log = LoggerFactory.getLogger(JsonAutomationStore.class);

    private final Path storageFile;
    private final ObjectMapper objectMapper;
    private State state;

    public JsonAutomationStore(Path storageRoot) {
        this.storageFile = storageRoot.resolve("automation.json");
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.state = load();
    }

    @Override
    public synchronized Reminder saveReminder(Reminder reminder) {
        state.reminders.removeIf(existing -> existing.id().equals(reminder.id()));
        state.reminders.add(reminder);
        state.reminders.sort(Comparator.comparing(Reminder::remindAt));
        persist();
        return reminder;
    }

    @Override
    public synchronized Optional<Reminder> findReminder(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return state.reminders.stream()
                .filter(reminder -> reminder.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized List<Reminder> listReminders(ReminderStatus status) {
        return state.reminders.stream()
                .filter(reminder -> status == null || reminder.status() == status)
                .sorted(Comparator.comparing(Reminder::remindAt))
                .toList();
    }

    @Override
    public synchronized ScheduleItem saveScheduleItem(ScheduleItem item) {
        state.scheduleItems.removeIf(existing -> existing.id().equals(item.id()));
        state.scheduleItems.add(item);
        state.scheduleItems.sort(Comparator.comparing(ScheduleItem::startAt));
        persist();
        return item;
    }

    @Override
    public synchronized Optional<ScheduleItem> findScheduleItem(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return state.scheduleItems.stream()
                .filter(item -> item.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive) {
        return listScheduleItems(fromInclusive, toExclusive, null);
    }

    @Override
    public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive,
                                                             Instant toExclusive,
                                                             ScheduleItemStatus status) {
        return state.scheduleItems.stream()
                .filter(item -> status == null || effectiveStatus(item) == status)
                .filter(item -> !item.startAt().isAfter(toExclusive) || !item.endAt().isBefore(fromInclusive))
                .filter(item -> item.startAt().isBefore(toExclusive) && item.endAt().isAfter(fromInclusive))
                .sorted(Comparator.comparing(ScheduleItem::startAt))
                .toList();
    }

    @Override
    public synchronized RecurringTask saveRecurringTask(RecurringTask task) {
        state.recurringTasks.removeIf(existing -> existing.id().equals(task.id()));
        state.recurringTasks.add(task);
        state.recurringTasks.sort(Comparator.comparing(RecurringTask::nextRunAt));
        persist();
        return task;
    }

    @Override
    public synchronized Optional<RecurringTask> findRecurringTask(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return state.recurringTasks.stream()
                .filter(task -> task.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized List<RecurringTask> listRecurringTasks(RecurringTaskStatus status) {
        return state.recurringTasks.stream()
                .filter(task -> status == null || task.status() == status)
                .sorted(Comparator.comparing(RecurringTask::nextRunAt))
                .toList();
    }

    @Override
    public synchronized Optional<RecipientBinding> getRecipientBinding() {
        return Optional.ofNullable(state.recipientBinding);
    }

    @Override
    public synchronized RecipientBinding saveRecipientBinding(RecipientBinding binding) {
        state.recipientBinding = binding;
        persist();
        return binding;
    }

    /**
     * Atomic claim of a reminder status transition. The whole check-and-set happens
     * under the store monitor so concurrent triggers cannot both pass the status check.
     */
    @Override
    public synchronized Optional<Reminder> transitionReminderStatus(String id, ReminderStatus expectedStatus,
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

    private State load() {
        try {
            Files.createDirectories(storageFile.getParent());
            if (!Files.exists(storageFile)) {
                return new State();
            }
            State loaded = objectMapper.readValue(storageFile.toFile(), State.class);
            return loaded != null ? loaded.normalize() : new State();
        } catch (IOException e) {
            // A corrupted store must not prevent the application from starting:
            // quarantine the broken file and start fresh.
            log.error("Failed to load automation store {}, quarantining it and starting empty: {}",
                    storageFile, e.getMessage());
            quarantineCorruptFile();
            return new State();
        }
    }

    private void quarantineCorruptFile() {
        try {
            Path corruptCopy = storageFile.resolveSibling(
                    storageFile.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(storageFile, corruptCopy, StandardCopyOption.REPLACE_EXISTING);
            log.info("corrupted automation store moved to {}", corruptCopy);
        } catch (IOException moveError) {
            log.warn("failed to quarantine corrupted automation store: {}", moveError.getMessage());
        }
    }

    /**
     * Persist via write-to-temp-then-atomic-move so a crash mid-write cannot leave a
     * truncated automation.json behind.
     */
    private void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            Path tempFile = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state);
            try {
                Files.move(tempFile, storageFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Filesystem does not support atomic move; fall back to a plain replace.
                Files.move(tempFile, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist automation store: " + storageFile, e);
        }
    }

    static class State {
        public List<Reminder> reminders = new ArrayList<>();
        public List<ScheduleItem> scheduleItems = new ArrayList<>();
        public List<RecurringTask> recurringTasks = new ArrayList<>();
        public RecipientBinding recipientBinding;

        State normalize() {
            if (reminders == null) {
                reminders = new ArrayList<>();
            }
            if (scheduleItems == null) {
                scheduleItems = new ArrayList<>();
            }
            if (recurringTasks == null) {
                recurringTasks = new ArrayList<>();
            }
            return this;
        }
    }

    private static ScheduleItemStatus effectiveStatus(ScheduleItem item) {
        return item.status() != null ? item.status() : ScheduleItemStatus.ACTIVE;
    }
}
