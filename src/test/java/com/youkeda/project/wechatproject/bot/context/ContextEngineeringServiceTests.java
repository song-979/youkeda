package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.agent.AgentContextAssembler;
import com.youkeda.project.wechatproject.bot.agent.AgentExecutionContext;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextEngineeringServiceTests {

    @Test
    void slidingWindowSummarizesOldHistoryAndKeepsRequiredMessages() {
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        properties.setRecentRawHistoryMessages(2);
        properties.setOlderHistorySummaryMaxChars(500);
        DefaultContextEngineeringService service = service(
                request -> ContextRelevance.CONTINUATION, properties, new ContextBudget(4_000, 0.2));

        ContextPackage context = service.build(ContextBuildRequest.builder()
                .currentMessage("continue now")
                .stage(ContextStage.PLAN)
                .recentHistory(List.of(
                        message("user", "old-one"),
                        message("assistant", "old-two"),
                        message("user", "old-three"),
                        message("assistant", "recent-one"),
                        message("user", "recent-two")))
                .fixedPromptMessages(List.of(message("system", "fixed-schema")))
                .build());

        String all = content(context.messages());
        assertThat(context.messages().getFirst().getContent()).isEqualTo("fixed-schema");
        assertThat(context.messages().getLast().getContent()).isEqualTo("continue now");
        assertThat(all).contains("Earlier conversation summary", "old-one", "recent-one", "recent-two");
        assertThat(context.compressionActions())
                .anyMatch(action -> action.action().equals("sliding-summarize-old-history"));
    }

    @Test
    void overEightyPercentCompactsLowRetentionLayersBeforeDagResults() {
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        properties.setCompressedToolResultsMaxChars(300);
        DefaultContextEngineeringService service = service(
                request -> ContextRelevance.TOOL_DEPENDENT, properties, new ContextBudget(260, 0.2));
        ContextTaskState taskState = new ContextTaskState(
                "wf-1", "RUNNING", 1, "node-2", null, "publish article",
                List.of(new ContextTaskRecord(
                        "node-1", "write", "CHAT", "SUCCEEDED", List.of(),
                        "write article", "important-result-" + "x".repeat(1_000), null, null, Map.of())));

        ContextPackage context = service.build(ContextBuildRequest.builder()
                .currentMessage("publish the result")
                .stage(ContextStage.EXECUTE)
                .audience(ContextAudience.SUB_AGENT)
                .taskState(taskState)
                .agentCapabilities(List.of(new AgentCapabilityView(
                        "CHAT", "large capability " + "c".repeat(1_000), List.of("chat"), "text")))
                .fixedPromptMessages(List.of(message("system", "required-system")))
                .budget(new ContextBudget(260, 0.2))
                .build());

        assertThat(context.compressionActions()).isNotEmpty();
        assertThat(context.compressionActions().getFirst().layer()).isEqualTo("capabilities");
        assertThat(content(context.messages())).contains("required-system", "publish the result");
        assertThat(context.messages().getLast().getContent()).isEqualTo("publish the result");
    }

    @Test
    void typedAgentContextIgnoresLegacyFullHistoryParameter() {
        DefaultContextEngineeringService service = service(
                request -> ContextRelevance.TOOL_DEPENDENT,
                ContextEngineeringProperties.defaults(), new ContextBudget(4_000, 0.2));
        ContextTaskState dependencyState = new ContextTaskState(
                "wf", "RUNNING", 1, "child", null, null,
                List.of(new ContextTaskRecord(
                        "parent", "parent", "CHAT", "SUCCEEDED", List.of(),
                        "prepare", "dependency-output", null, null, Map.of(
                                "tool_transcript_session", "session-parent",
                                "tool_initial_context_reference",
                                "tool-transcript://session-parent/context-initial"))));
        AgentTask task = new AgentTask("BROWSER", "publish dependency", Map.of(
                "history", List.of(message("user", "SECRET FULL CONVERSATION"))))
                .withExecutionContext(AgentExecutionContext.isolated(
                        "user", dependencyState, List.of(), null));

        ContextPackage context = AgentContextAssembler.build(service, task, "browser-system", null);
        String all = content(context.messages());

        assertThat(all).contains("browser-system", "dependency-output", "publish dependency",
                "tool_transcript_session=session-parent",
                "tool_initial_context_reference=tool-transcript://session-parent/context-initial");
        assertThat(all).doesNotContain("SECRET FULL CONVERSATION");
    }

    private static DefaultContextEngineeringService service(
            ContextRelevanceClassifier classifier,
            ContextEngineeringProperties properties,
            ContextBudget budget) {
        return new DefaultContextEngineeringService(
                classifier, new CharacterContextTokenEstimator(), properties, budget);
    }

    private static ChatRequest.Message message(String role, String content) {
        return new ChatRequest.Message(role, content);
    }

    private static String content(List<ChatRequest.Message> messages) {
        return messages.stream().map(message -> String.valueOf(message.getContent()))
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
