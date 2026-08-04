package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailurePolicyTests {

    private final FailurePolicy policy = new FailurePolicy();

    @Test
    void retriesTransientFailuresWithinAttemptLimit() {
        AgentResult timeout = AgentResult.failed("n1", "timeout", AgentResult.ErrorKind.TIMEOUT);
        AgentResult tool = AgentResult.failed("n1", "tool failed", AgentResult.ErrorKind.TOOL);

        assertThat(policy.shouldRetry(timeout, 1, 3)).isTrue();
        assertThat(policy.shouldRetry(tool, 2, 3)).isTrue();
        assertThat(policy.shouldRetry(timeout, 3, 3)).isFalse();
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
}
