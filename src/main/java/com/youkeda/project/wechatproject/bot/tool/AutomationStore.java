package com.youkeda.project.wechatproject.bot.tool;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationStore {

    Reminder saveReminder(Reminder reminder);

    Optional<Reminder> findReminder(String id);

    List<Reminder> listReminders(ReminderStatus status);

    ScheduleItem saveScheduleItem(ScheduleItem item);

    List<ScheduleItem> listScheduleItems(Instant fromInclusive, Instant toExclusive);

    Optional<RecipientBinding> getRecipientBinding();

    RecipientBinding saveRecipientBinding(RecipientBinding binding);

    enum ReminderStatus {
        PENDING,
        TRIGGERING,
        SENT,
        CANCELLED,
        FAILED,
        MISSED
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
            int sendAttempts) {
    }

    record ScheduleItem(
            String id,
            String title,
            Instant startAt,
            Instant endAt,
            String notes,
            Instant createdAt,
            Instant updatedAt) {
    }

    record RecipientBinding(String recipientId, Instant boundAt, Instant updatedAt) {
    }
}
