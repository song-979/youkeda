package com.youkeda.project.wechatproject.bot.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultTests {

    @Test
    void classifiesCommonFailuresAndHonorsExplicitKind() {
        assertThat(AgentResult.failed("1", "HTTP 429 rate limit").errorKind())
                .isEqualTo(AgentResult.ErrorKind.RATE_LIMIT);
        assertThat(AgentResult.failed("2", "request timed out").errorKind())
                .isEqualTo(AgentResult.ErrorKind.TIMEOUT);
        assertThat(AgentResult.failed("3", "bad api key").errorKind())
                .isEqualTo(AgentResult.ErrorKind.AUTH);
        assertThat(AgentResult.failed("4", "custom", AgentResult.ErrorKind.TOOL).errorKind())
                .isEqualTo(AgentResult.ErrorKind.TOOL);
    }
}
