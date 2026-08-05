package com.youkeda.project.wechatproject.bot.tool.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Invocation-local receipts for state-changing automation tool calls. */
public final class AutomationEvidenceContext {

    private static final ThreadLocal<List<Evidence>> EVIDENCE = ThreadLocal.withInitial(ArrayList::new);

    private AutomationEvidenceContext() {}

    public static void clear() {
        EVIDENCE.remove();
    }

    public static void recordFailure(EntityType type, String message) {
        EVIDENCE.get().add(new Evidence(type, null, null, null, null, false, message));
    }

    public static void recordReminder(AutomationStore.Reminder reminder) {
        if (reminder == null) return;
        EVIDENCE.get().add(new Evidence(EntityType.REMINDER, reminder.id(), reminder.ownerId(),
                reminder.status().name(), reminder.remindAt(), true, null));
    }

    public static void recordSchedule(AutomationStore.ScheduleItem item) {
        if (item == null) return;
        EVIDENCE.get().add(new Evidence(EntityType.SCHEDULE, item.id(), null,
                AutomationStore.effectiveStatus(item).name(), item.startAt(), true, null));
    }

    public static void recordRecurring(AutomationStore.RecurringTask task) {
        if (task == null) return;
        EVIDENCE.get().add(new Evidence(EntityType.RECURRING, task.id(), task.ownerId(),
                task.status().name(), task.nextRunAt(), true, null));
    }

    public static List<Evidence> drain() {
        List<Evidence> values = List.copyOf(EVIDENCE.get());
        EVIDENCE.remove();
        return values;
    }

    public enum EntityType { REMINDER, SCHEDULE, RECURRING }

    public record Evidence(EntityType type, String entityId, String ownerId,
                           String expectedStatus, Instant expectedTime,
                           boolean operationSucceeded, String message) {}
}
