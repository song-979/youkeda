package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class JsonAutomationStore implements AutomationStore {

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
    public synchronized List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive) {
        return state.scheduleItems.stream()
                .filter(item -> !item.startAt().isBefore(fromInclusive) && item.startAt().isBefore(toExclusive))
                .sorted(Comparator.comparing(ScheduleItem::startAt))
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

    private State load() {
        try {
            Files.createDirectories(storageFile.getParent());
            if (!Files.exists(storageFile)) {
                return new State();
            }
            State loaded = objectMapper.readValue(storageFile.toFile(), State.class);
            return loaded != null ? loaded.normalize() : new State();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load automation store: " + storageFile, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile.toFile(), state);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist automation store: " + storageFile, e);
        }
    }

    static class State {
        public List<Reminder> reminders = new ArrayList<>();
        public List<ScheduleItem> scheduleItems = new ArrayList<>();
        public RecipientBinding recipientBinding;

        State normalize() {
            if (reminders == null) {
                reminders = new ArrayList<>();
            }
            if (scheduleItems == null) {
                scheduleItems = new ArrayList<>();
            }
            return this;
        }
    }
}
