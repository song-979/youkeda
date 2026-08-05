package com.youkeda.project.wechatproject.bot.validation;

import com.youkeda.project.wechatproject.bot.tool.chat.AutomationEvidenceContext;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationStore;

import java.util.List;
import java.util.Objects;

/** Re-reads automation entities from persistence before a successful Agent result is trusted. */
public class AutomationPersistenceVerifier implements PersistenceEvidenceVerifier {

    private final AutomationStore store;

    public AutomationPersistenceVerifier(AutomationStore store) {
        this.store = store;
    }

    @Override
    public Verification verify(List<AutomationEvidenceContext.Evidence> evidence, String recipientId) {
        if (evidence == null || evidence.isEmpty()) return Verification.accepted(0);
        int verified = 0;
        for (AutomationEvidenceContext.Evidence item : evidence) {
            if (!item.operationSucceeded()) {
                return Verification.rejected(nonBlank(item.message(), "automation operation failed"));
            }
            if (item.entityId() == null || item.entityId().isBlank()) {
                return Verification.rejected("automation operation returned no persistent id");
            }
            boolean matches = switch (item.type()) {
                case REMINDER -> store.findReminder(item.entityId()).map(value ->
                        Objects.equals(item.expectedStatus(), value.status().name())
                                && Objects.equals(item.expectedTime(), value.remindAt())
                                && ownerMatches(item.ownerId(), recipientId, value.ownerId())).orElse(false);
                case SCHEDULE -> store.findScheduleItem(item.entityId()).map(value ->
                        Objects.equals(item.expectedStatus(), AutomationStore.effectiveStatus(value).name())
                                && Objects.equals(item.expectedTime(), value.startAt())).orElse(false);
                case RECURRING -> store.findRecurringTask(item.entityId()).map(value ->
                        Objects.equals(item.expectedStatus(), value.status().name())
                                && Objects.equals(item.expectedTime(), value.nextRunAt())
                                && ownerMatches(item.ownerId(), recipientId, value.ownerId())).orElse(false);
            };
            if (!matches) {
                return Verification.rejected("automation persistence verification failed for " + item.entityId());
            }
            verified++;
        }
        return Verification.accepted(verified);
    }

    private static boolean ownerMatches(String receiptOwner, String recipientId, String storedOwner) {
        String expected = receiptOwner != null && !receiptOwner.isBlank() ? receiptOwner : recipientId;
        return expected == null || expected.isBlank() || Objects.equals(expected, storedOwner);
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
