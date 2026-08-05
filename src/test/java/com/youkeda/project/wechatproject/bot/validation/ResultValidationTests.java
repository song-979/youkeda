package com.youkeda.project.wechatproject.bot.validation;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactCollector;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactStore;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactValidator;
import com.youkeda.project.wechatproject.bot.artifact.IndexedArtifactStore;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationEvidenceContext;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationStore;
import com.youkeda.project.wechatproject.bot.tool.chat.JsonAutomationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultValidationTests {

    @TempDir
    Path tempDir;

    @Test
    void emptySuccessIsRejected() {
        ArtifactStore store = new IndexedArtifactStore(tempDir.resolve("artifacts.db"),
                tempDir.resolve("blobs"), new ArtifactValidator());
        AgentResultGuard guard = new AgentResultGuard(new ArtifactCollector(store, null), null);
        guard.beginInvocation();

        AgentResult result = guard.validate(AgentResult.success("task-1", null, null),
                new AgentResultGuard.GuardContext("user-1", "task-1", "run-1",
                        "node-1", 1, "CHAT"));

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(AgentResult.ErrorKind.VALIDATION, result.errorKind());
        assertEquals("rejected", result.signals().get("guard.result"));
    }

    @Test
    void localFileMarkerWithoutCorrelatedPayloadIsRejected() {
        ArtifactStore store = new IndexedArtifactStore(tempDir.resolve("marker-artifacts.db"),
                tempDir.resolve("marker-blobs"), new ArtifactValidator());
        AgentResultGuard guard = new AgentResultGuard(new ArtifactCollector(store, null), null);
        guard.beginInvocation();

        AgentResult result = guard.validate(
                AgentResult.success("task-2", "[LOCAL_FILE:C:/temp/report.pdf]\nready",
                        "[LOCAL_FILE:C:/temp/report.pdf]\nready"),
                new AgentResultGuard.GuardContext("user-1", "task-2", "run-2",
                        "node-2", 1, "CHAT"));

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertTrue(result.errorMessage().contains("no correlated prepared file"));
    }

    @Test
    void automationEvidenceMustMatchPersistedOwnerStateAndTime() {
        JsonAutomationStore store = new JsonAutomationStore(tempDir.resolve("automation"));
        Instant time = Instant.parse("2026-08-08T00:00:00Z");
        AutomationStore.Reminder reminder = new AutomationStore.Reminder(
                "reminder-1", "check documents", time, "check documents",
                AutomationStore.ReminderStatus.PENDING, Instant.now(), Instant.now(),
                null, 0).withOwnerId("user-1");
        store.saveReminder(reminder);
        AutomationPersistenceVerifier verifier = new AutomationPersistenceVerifier(store);

        var valid = verifier.verify(List.of(new AutomationEvidenceContext.Evidence(
                AutomationEvidenceContext.EntityType.REMINDER, reminder.id(), "user-1",
                reminder.status().name(), time, true, null)), "user-1");
        assertTrue(valid.valid());
        assertEquals(1, valid.verifiedCount());

        var wrongOwner = verifier.verify(List.of(new AutomationEvidenceContext.Evidence(
                AutomationEvidenceContext.EntityType.REMINDER, reminder.id(), "user-2",
                reminder.status().name(), time, true, null)), "user-2");
        assertFalse(wrongOwner.valid());

        var failedOperation = verifier.verify(List.of(new AutomationEvidenceContext.Evidence(
                AutomationEvidenceContext.EntityType.REMINDER, null, null,
                null, null, false, "recipient is not bound")), "user-1");
        assertFalse(failedOperation.valid());
    }
}
