package com.youkeda.project.wechatproject.bot.validation;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactCollector;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactRef;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationEvidenceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structural result checks only; model-authored text is never rewritten. */
public class AgentResultGuard {

    private final ArtifactCollector artifactCollector;
    private final PersistenceEvidenceVerifier persistenceVerifier;

    public AgentResultGuard(ArtifactCollector artifactCollector,
                            PersistenceEvidenceVerifier persistenceVerifier) {
        this.artifactCollector = artifactCollector;
        this.persistenceVerifier = persistenceVerifier;
    }

    public void beginInvocation() {
        AutomationEvidenceContext.clear();
        artifactCollector.clearThreadArtifacts();
    }

    public AgentResult validate(AgentResult original, GuardContext context) {
        if (original == null) {
            AutomationEvidenceContext.clear();
            return AgentResult.failed(context.requestId(), "agent returned no result", AgentResult.ErrorKind.VALIDATION);
        }
        AgentResult result;
        try {
            result = artifactCollector.collect(original, new ArtifactCollector.CollectionContext(
                    context.recipientId(), context.requestId(), context.runId(), context.nodeId(),
                    context.revision(), context.agentType()));
        } catch (Exception e) {
            AutomationEvidenceContext.clear();
            return rejected(original, "artifact validation failed: " + e.getMessage());
        }

        List<AutomationEvidenceContext.Evidence> evidence = AutomationEvidenceContext.drain();
        PersistenceEvidenceVerifier.Verification verification = persistenceVerifier != null
                ? persistenceVerifier.verify(evidence, context.recipientId())
                : evidence.isEmpty() ? PersistenceEvidenceVerifier.Verification.accepted(0)
                : PersistenceEvidenceVerifier.Verification.rejected("persistence verifier is unavailable");
        if (!verification.valid()) return rejected(result, verification.message());

        if (result.status() == AgentResult.Status.SUCCESS && !hasUsableResult(result)) {
            return rejected(result, "agent returned an empty successful result");
        }
        Map<String, String> signals = new LinkedHashMap<>(result.signals());
        signals.put("guard.result", "verified");
        if (verification.verifiedCount() > 0) {
            signals.put("persistence.evidence", "verified");
            signals.put("persistence.evidence.count", String.valueOf(verification.verifiedCount()));
        }
        return result.withSignals(signals);
    }

    private static boolean hasUsableResult(AgentResult result) {
        if (!result.artifacts().isEmpty()) return true;
        if (result.output() instanceof String value && !value.isBlank()) return true;
        if (result.output() != null) return true;
        return result.rawOutput() != null && !result.rawOutput().isBlank();
    }

    private static AgentResult rejected(AgentResult result, String message) {
        Map<String, String> signals = new LinkedHashMap<>(result.signals());
        signals.put("guard.result", "rejected");
        return new AgentResult(result.taskId(), AgentResult.Status.FAILED,
                result.output(), result.rawOutput(), message, AgentResult.ErrorKind.VALIDATION,
                result.messageToUser(), result.resumeState(), List.of(), signals, result.artifacts());
    }

    public record GuardContext(String recipientId, String requestId, String runId,
                               String nodeId, int revision, String agentType) {}
}
