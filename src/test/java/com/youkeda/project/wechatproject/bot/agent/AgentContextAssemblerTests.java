package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.context.ContextBudgetReport;
import com.youkeda.project.wechatproject.bot.context.ContextBuildRequest;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ContextRelevance;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextAssemblerTests {

    @Test
    void ignoresLegacyHistoryParameterWhenTypedExecutionContextIsMissing() {
        AtomicReference<ContextBuildRequest> captured = new AtomicReference<>();
        ChatRequest.Message leakedHistory = new ChatRequest.Message("user", "private sibling context");
        AgentTask task = new AgentTask("CHAT", "current instruction",
                Map.of("history", List.of(leakedHistory)));

        AgentContextAssembler.build(request -> {
            captured.set(request);
            return new ContextPackage(List.of(), ContextRelevance.NEW_TOPIC,
                    new ContextBudgetReport(0, 0, false), List.of());
        }, task, "fixed prompt", null);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().recentHistory()).isEmpty();
    }
}
