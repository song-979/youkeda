package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FailurePolicyTests {

    private final FailurePolicy policy = new FailurePolicy();

    @Test
    void retriesTransientFailuresWithinAttemptLimit() {
        AgentResult timeout = AgentResult.failed("n1", "timeout", AgentResult.ErrorKind.TIMEOUT);
        AgentResult tool = AgentResult.failed("n1", "tool failed", AgentResult.ErrorKind.TOOL);
        AgentResult upstream = AgentResult.failed(
                "n1", "503 service unavailable", AgentResult.ErrorKind.UPSTREAM);

        assertThat(policy.shouldRetry(timeout, 1, 3)).isTrue();
        assertThat(policy.shouldRetry(upstream, 2, 3)).isTrue();
        assertThat(policy.shouldRetry(tool, 2, 3)).isFalse();
        assertThat(policy.shouldRetry(timeout, 3, 3)).isFalse();
    }

    @Test
    void defersOnlyVerifiedTransientFailuresOnSafeNodes() {
        DagNode safeNode = node("CHAT", 1);
        safeNode.beginAttempt();
        AgentResult upstream = AgentResult.failed(
                "n1", "503 service unavailable", AgentResult.ErrorKind.UPSTREAM);

        FailurePolicy.DeferredRetryDecision accepted = policy.deferredRetry(
                upstream, null, safeNode, 2);
        FailurePolicy.DeferredRetryDecision unknown = policy.deferredRetry(
                AgentResult.failed("n1", "something failed", AgentResult.ErrorKind.UNKNOWN),
                null, safeNode, 2);

        DagNode sideEffectNode = node("BROWSER", 1);
        sideEffectNode.beginAttempt();
        FailurePolicy.DeferredRetryDecision unsafe = policy.deferredRetry(
                upstream, null, sideEffectNode, 2);

        assertThat(accepted.retryLater()).isTrue();
        assertThat(accepted.deferredAttempt()).isEqualTo(1);
        assertThat(unknown.retryLater()).isFalse();
        assertThat(unsafe.retryLater()).isFalse();
    }

    @Test
    void repeatedDeferredFailureBecomesTerminal() {
        DagNode node = node("CHAT", 1);
        AgentResult failure = AgentResult.failed(
                "n1", "503 service unavailable", AgentResult.ErrorKind.UPSTREAM);
        node.beginAttempt();
        node.setResult(failure);
        node.beginAttempt();

        FailurePolicy.DeferredRetryDecision decision = policy.deferredRetry(
                failure, node.result(), node, 2);

        assertThat(decision.retryLater()).isFalse();
        assertThat(decision.reason()).contains("same failure repeated");
    }

    @Test
    void routesAuthAndValidationFailuresToUser() {
        AgentResult auth = AgentResult.failed("n1", "token expired", AgentResult.ErrorKind.AUTH);
        AgentResult validation = AgentResult.failed("n2", "missing city", AgentResult.ErrorKind.VALIDATION);

        assertThat(policy.shouldRetry(auth, 1, 3)).isFalse();
        assertThat(policy.needsUserInput(auth)).isTrue();
        assertThat(policy.needsUserInput(validation)).isTrue();
        assertThat(policy.userMessage(validation)).contains("missing city");
    }

    private static DagNode node(String agentType, int maxAttempts) {
        return new DagNode("n1", "node", agentType, "test", null,
                List.of(), Map.of(), maxAttempts);
    }
}
