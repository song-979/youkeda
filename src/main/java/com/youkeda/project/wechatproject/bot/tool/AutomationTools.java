package com.youkeda.project.wechatproject.bot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class AutomationTools implements ToolService.ProjectTool {

    private final AutomationRuntime runtime;
    private final AutomationProperties properties;
    private final ZoneId zoneId;

    public AutomationTools(AutomationRuntime runtime, AutomationProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
        this.zoneId = ZoneId.of(properties.getTimeZone());
    }

    @Tool(name = "create_reminder", description = "Create a one-time private reminder. Use ISO datetime with timezone.")
    public String createReminder(
            @ToolParam(description = "Short reminder title.") String title,
            @ToolParam(description = "Reminder time, ISO format, for example 2026-07-22T20:00:00+08:00.") String remindAt,
            @ToolParam(required = false, description = "Message to send when the reminder triggers.") String message) {
        AutomationRuntime.ReminderResult result = runtime.createReminder(title, remindAt, message);
        if (!result.success()) {
            return "Failed to create reminder: " + result.message();
        }
        AutomationStore.Reminder reminder = result.reminder();
        return "Reminder created: id=" + reminder.id()
                + ", title=" + reminder.title()
                + ", remindAt=" + format(reminder.remindAt())
                + ", status=" + reminder.status();
    }

    @Tool(name = "list_reminders", description = "List private reminders by status. Omit status to list all reminders. Reminder status: PENDING, TRIGGERING, SENT, CANCELLED, FAILED, MISSED, DELETED.")
    public String listReminders(
            @ToolParam(required = false, description = "Optional status: PENDING, TRIGGERING, SENT, CANCELLED, FAILED, MISSED, DELETED.") String status) {
        AutomationStore.ReminderStatus parsedStatus;
        try {
            parsedStatus = parseStatus(status);
        } catch (IllegalArgumentException e) {
            return "Invalid reminder status. Use PENDING, TRIGGERING, SENT, CANCELLED, FAILED, MISSED, or DELETED.";
        }
        List<AutomationStore.Reminder> reminders = runtime.listReminders(parsedStatus);
        if (reminders.isEmpty()) {
            return "No reminders found.";
        }
        StringBuilder sb = new StringBuilder("Reminders:\n");
        for (AutomationStore.Reminder reminder : reminders) {
            sb.append("- ")
                    .append(reminder.id())
                    .append(" [").append(reminder.status()).append("] ")
                    .append(format(reminder.remindAt()))
                    .append(" ")
                    .append(reminder.title())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "update_reminder", description = "Update a pending one-time private reminder. Leave optional fields empty to keep existing values.")
    public String updateReminder(
            @ToolParam(description = "Reminder id, for example R-1234ABCD.") String id,
            @ToolParam(required = false, description = "New short reminder title.") String title,
            @ToolParam(required = false, description = "New reminder time, ISO format with timezone.") String remindAt,
            @ToolParam(required = false, description = "New message to send when the reminder triggers.") String message) {
        AutomationRuntime.ReminderResult result = runtime.updateReminder(id, title, remindAt, message);
        if (!result.success()) {
            return "Failed to update reminder: " + result.message();
        }
        AutomationStore.Reminder reminder = result.reminder();
        return "Reminder updated: id=" + reminder.id()
                + ", title=" + reminder.title()
                + ", remindAt=" + format(reminder.remindAt())
                + ", status=" + reminder.status();
    }

    @Tool(name = "cancel_reminder", description = "Cancel a pending private reminder by id.")
    public String cancelReminder(@ToolParam(description = "Reminder id, for example R-1234ABCD.") String id) {
        AutomationRuntime.ReminderResult result = runtime.cancelReminder(id);
        if (!result.success()) {
            return "Failed to cancel reminder: " + result.message();
        }
        return "Reminder cancelled: id=" + result.reminder().id() + ", title=" + result.reminder().title();
    }

    @Tool(name = "delete_reminder", description = "Soft-delete a private reminder by id. Deleted reminders are hidden from pending lists but can be listed with status DELETED.")
    public String deleteReminder(@ToolParam(description = "Reminder id, for example R-1234ABCD.") String id) {
        AutomationRuntime.ReminderResult result = runtime.deleteReminder(id);
        if (!result.success()) {
            return "Failed to delete reminder: " + result.message();
        }
        return "Reminder deleted: id=" + result.reminder().id() + ", title=" + result.reminder().title();
    }

    @Tool(name = "create_schedule_item", description = "Create a private schedule item. Schedule items record time blocks only; they do not send reminders by themselves.")
    public String createScheduleItem(
            @ToolParam(description = "Short schedule title.") String title,
            @ToolParam(description = "Start time, ISO format, for example 2026-07-23T15:00:00+08:00.") String startAt,
            @ToolParam(description = "End time, ISO format, for example 2026-07-23T16:00:00+08:00.") String endAt,
            @ToolParam(required = false, description = "Optional schedule notes.") String notes) {
        AutomationRuntime.ScheduleResult result = runtime.createScheduleItem(title, startAt, endAt, notes);
        if (!result.success()) {
            return "Failed to create schedule item: " + result.message();
        }
        AutomationStore.ScheduleItem item = result.item();
        return "Schedule item created: id=" + item.id()
                + ", title=" + item.title()
                + ", startAt=" + format(item.startAt())
                + ", endAt=" + format(item.endAt());
    }

    @Tool(name = "update_schedule_item", description = "Update a private schedule item. Schedule status: ACTIVE, CANCELLED, COMPLETED, DELETED.")
    public String updateScheduleItem(
            @ToolParam(description = "Schedule item id, for example S-1234ABCD.") String id,
            @ToolParam(required = false, description = "New short schedule title.") String title,
            @ToolParam(required = false, description = "New start time, ISO format.") String startAt,
            @ToolParam(required = false, description = "New end time, ISO format.") String endAt,
            @ToolParam(required = false, description = "New optional schedule notes.") String notes,
            @ToolParam(required = false, description = "Optional status: ACTIVE, CANCELLED, COMPLETED, DELETED.") String status) {
        AutomationStore.ScheduleItemStatus parsedStatus;
        try {
            parsedStatus = parseScheduleStatus(status);
        } catch (IllegalArgumentException e) {
            return "Invalid schedule status. Use ACTIVE, CANCELLED, COMPLETED, or DELETED.";
        }
        AutomationRuntime.ScheduleResult result = runtime.updateScheduleItem(id, title, startAt, endAt, notes, parsedStatus);
        if (!result.success()) {
            return "Failed to update schedule item: " + result.message();
        }
        AutomationStore.ScheduleItem item = result.item();
        return "Schedule item updated: id=" + item.id()
                + ", title=" + item.title()
                + ", startAt=" + format(item.startAt())
                + ", endAt=" + format(item.endAt())
                + ", status=" + item.status();
    }

    @Tool(name = "delete_schedule_item", description = "Soft-delete a private schedule item by id. Deleted items can be listed with status DELETED.")
    public String deleteScheduleItem(@ToolParam(description = "Schedule item id, for example S-1234ABCD.") String id) {
        AutomationRuntime.ScheduleResult result = runtime.deleteScheduleItem(id);
        if (!result.success()) {
            return "Failed to delete schedule item: " + result.message();
        }
        return "Schedule item deleted: id=" + result.item().id() + ", title=" + result.item().title();
    }

    @Tool(name = "list_schedule_items", description = "List private schedule items that overlap a time range. Optional status: ACTIVE, CANCELLED, COMPLETED, DELETED.")
    public String listScheduleItems(
            @ToolParam(description = "Range start, ISO format.") String from,
            @ToolParam(description = "Range end, ISO format.") String to,
            @ToolParam(required = false, description = "Optional status: ACTIVE, CANCELLED, COMPLETED, DELETED.") String status) {
        AutomationStore.ScheduleItemStatus parsedStatus;
        try {
            parsedStatus = parseScheduleStatus(status);
        } catch (IllegalArgumentException e) {
            return "Invalid schedule status. Use ACTIVE, CANCELLED, COMPLETED, or DELETED.";
        }
        List<AutomationStore.ScheduleItem> items = runtime.listScheduleItems(from, to, parsedStatus);
        if (items.isEmpty()) {
            return "No schedule items found.";
        }
        StringBuilder sb = new StringBuilder("Schedule items:\n");
        for (AutomationStore.ScheduleItem item : items) {
            sb.append("- ")
                    .append(item.id())
                    .append(" ")
                    .append(format(item.startAt()))
                    .append(" - ")
                    .append(format(item.endAt()))
                    .append(" ")
                    .append(item.title())
                    .append(" [").append(item.status()).append("]")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "create_recurring_reminder", description = "Create a recurring private reminder. DAILY uses HH:mm, WEEKLY uses DAY_OF_WEEK HH:mm, CRON uses a Spring cron expression with seconds.")
    public String createRecurringReminder(
            @ToolParam(description = "Short reminder title.") String title,
            @ToolParam(description = "Schedule type: DAILY, WEEKLY, or CRON.") String scheduleType,
            @ToolParam(description = "Schedule expression. DAILY example: 09:30. WEEKLY example: FRIDAY 18:00. CRON example: 0 15 10 * * *.") String scheduleExpression,
            @ToolParam(required = false, description = "Message to send when each recurring reminder triggers.") String message) {
        AutomationStore.RecurringScheduleType parsedType;
        try {
            parsedType = AutomationStore.RecurringScheduleType.valueOf(scheduleType.trim().toUpperCase());
        } catch (Exception e) {
            return "Invalid recurring schedule type. Use DAILY, WEEKLY, or CRON.";
        }
        AutomationRuntime.RecurringTaskResult result = runtime.createRecurringReminder(
                title, parsedType, scheduleExpression, message);
        if (!result.success()) {
            return "Failed to create recurring reminder: " + result.message();
        }
        AutomationStore.RecurringTask task = result.task();
        return "Recurring reminder created: id=" + task.id()
                + ", title=" + task.title()
                + ", type=" + task.scheduleType()
                + ", expression=" + task.scheduleExpression()
                + ", nextRunAt=" + format(task.nextRunAt())
                + ", status=" + task.status();
    }

    @Tool(name = "list_recurring_tasks", description = "List recurring reminder rules by status. Omit status to list all rules.")
    public String listRecurringTasks(
            @ToolParam(required = false, description = "Optional status: ACTIVE, PAUSED, CANCELLED, DELETED.") String status) {
        AutomationStore.RecurringTaskStatus parsedStatus;
        try {
            parsedStatus = parseRecurringStatus(status);
        } catch (IllegalArgumentException e) {
            return "Invalid recurring task status. Use ACTIVE, PAUSED, CANCELLED, or DELETED.";
        }
        List<AutomationStore.RecurringTask> tasks = runtime.listRecurringTasks(parsedStatus);
        if (tasks.isEmpty()) {
            return "No recurring tasks found.";
        }
        StringBuilder sb = new StringBuilder("Recurring tasks:\n");
        for (AutomationStore.RecurringTask task : tasks) {
            sb.append("- ")
                    .append(task.id())
                    .append(" [").append(task.status()).append("] ")
                    .append(task.scheduleType())
                    .append(" ")
                    .append(task.scheduleExpression())
                    .append(" next=")
                    .append(format(task.nextRunAt()))
                    .append(" ")
                    .append(task.title())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "delete_recurring_task", description = "Soft-delete a recurring reminder rule and delete its pending generated reminder instances.")
    public String deleteRecurringTask(@ToolParam(description = "Recurring task id, for example RR-1234ABCD.") String id) {
        AutomationRuntime.RecurringTaskResult result = runtime.deleteRecurringTask(id);
        if (!result.success()) {
            return "Failed to delete recurring task: " + result.message();
        }
        return "Recurring task deleted: id=" + result.task().id() + ", title=" + result.task().title();
    }

    private AutomationStore.ReminderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AutomationStore.ReminderStatus.valueOf(status.trim().toUpperCase());
    }

    private AutomationStore.ScheduleItemStatus parseScheduleStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AutomationStore.ScheduleItemStatus.valueOf(status.trim().toUpperCase());
    }

    private AutomationStore.RecurringTaskStatus parseRecurringStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AutomationStore.RecurringTaskStatus.valueOf(status.trim().toUpperCase());
    }

    private String format(java.time.Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zoneId));
    }
}
