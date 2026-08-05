package com.youkeda.project.wechatproject.bot.tool.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class JsonAutomationStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void atomicallyClaimsPendingReminderOnlyOnce() throws Exception {
        JsonAutomationStore store = new JsonAutomationStore(tempDir);
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        store.saveReminder(reminder("r1", "owner-a", now));

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> claims = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<Boolean>) () -> store.transitionReminderStatus(
                            "r1", AutomationStore.ReminderStatus.PENDING,
                            AutomationStore.ReminderStatus.TRIGGERING, now.plusSeconds(i)).isPresent())
                    .toList();
            long successfulClaims = executor.invokeAll(claims).stream()
                    .filter(future -> {
                        try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .count();

            assertThat(successfulClaims).isEqualTo(1);
            assertThat(store.findReminder("r1").orElseThrow().ownerId()).isEqualTo("owner-a");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void quarantinesCorruptFileAndStartsEmpty() throws Exception {
        Path file = tempDir.resolve("automation.json");
        Files.writeString(file, "{not-json");

        JsonAutomationStore store = new JsonAutomationStore(tempDir);

        assertThat(store.listReminders(null)).isEmpty();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("automation.json.corrupt-"));
        }
    }

    @Test
    void readsReminderWrittenByOwnerlessCompatibilityConstructor() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        JsonAutomationStore store = new JsonAutomationStore(tempDir);
        store.saveReminder(new AutomationStore.Reminder(
                "legacy", "legacy", now, "message", AutomationStore.ReminderStatus.PENDING,
                now, now, null, 0));

        JsonAutomationStore reloaded = new JsonAutomationStore(tempDir);

        assertThat(reloaded.findReminder("legacy").orElseThrow().ownerId()).isNull();
    }

    private static AutomationStore.Reminder reminder(String id, String ownerId, Instant now) {
        return new AutomationStore.Reminder(id, "title", now.plusSeconds(60), "message",
                AutomationStore.ReminderStatus.PENDING, now, now, null, 0,
                AutomationStore.AutomationActionType.TEXT, null, null,
                AutomationStore.AutomationTaskKind.TEXT_REMINDER, null, null, List.of(), 0, ownerId);
    }
}
