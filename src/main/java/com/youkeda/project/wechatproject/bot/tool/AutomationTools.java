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

    @Tool(name = "list_reminders", description = "List private reminders by status. Omit status to list all reminders.")
    public String listReminders(
            @ToolParam(required = false, description = "Optional status: PENDING, SENT, CANCELLED, FAILED, MISSED.") String status) {
        AutomationStore.ReminderStatus parsedStatus;
        try {
            parsedStatus = parseStatus(status);
        } catch (IllegalArgumentException e) {
            return "Invalid reminder status. Use PENDING, SENT, CANCELLED, FAILED, or MISSED.";
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

    @Tool(name = "cancel_reminder", description = "Cancel a pending private reminder by id.")
    public String cancelReminder(@ToolParam(description = "Reminder id, for example R-1234ABCD.") String id) {
        AutomationRuntime.ReminderResult result = runtime.cancelReminder(id);
        if (!result.success()) {
            return "Failed to cancel reminder: " + result.message();
        }
        return "Reminder cancelled: id=" + result.reminder().id() + ", title=" + result.reminder().title();
    }

    @Tool(name = "create_schedule_item", description = "Create a private schedule item. This records an event; it does not send a reminder by itself.")
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

    @Tool(name = "list_schedule_items", description = "List private schedule items in a time range.")
    public String listScheduleItems(
            @ToolParam(description = "Range start, ISO format.") String from,
            @ToolParam(description = "Range end, ISO format.") String to) {
        List<AutomationStore.ScheduleItem> items = runtime.listScheduleItems(from, to);
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
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private AutomationStore.ReminderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AutomationStore.ReminderStatus.valueOf(status.trim().toUpperCase());
    }

    private String format(java.time.Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zoneId));
    }
}
