package com.youkeda.project.wechatproject.bot.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLoopContextManagerTests {

    @TempDir
    Path tempDir;

    @Test
    void archivesFullResultsAndCompactsWorkingContextAtEightyPercent() throws Exception {
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        properties.setReservedOutputRatio(0.2);
        properties.setRecentRawToolRounds(1);
        properties.setCompressedToolResultsMaxChars(200);
        FileToolTranscriptStore store = new FileToolTranscriptStore(tempDir);
        ToolLoopContextManager manager = new ToolLoopContextManager(store, 2_000, properties);

        String firstResult = "first-result " + "alpha item ".repeat(1_000);
        String secondResult = "second-result " + "beta item ".repeat(1_000);
        Prompt prompt = toolPrompt(firstResult, secondResult);

        ToolLoopContextManager.PreparedPrompt prepared = manager.prepare(prompt, true, 3);

        assertThat(prepared.originalTokens()).isGreaterThan(1_600);
        assertThat(prepared.preparedTokens()).isLessThanOrEqualTo(1_600);
        assertThat(prepared.compressionActions()).isPositive();
        String workingToolResults = prepared.prompt().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(workingToolResults).contains("archived full result", "tool-transcript://");
        assertThat(store.read("tool-transcript://" + prepared.sessionId() + "/call-1"))
                .get().extracting(ToolTranscriptStore.ToolTranscriptEntry::responseData)
                .isEqualTo(firstResult);
        assertThat(store.read("tool-transcript://" + prepared.sessionId() + "/call-2"))
                .get().extracting(ToolTranscriptStore.ToolTranscriptEntry::responseData)
                .isEqualTo(secondResult);
        assertThat(store.list(prepared.sessionId(), "first-result", 20))
                .extracting(ToolTranscriptStore.ToolTranscriptSummary::reference)
                .contains("tool-transcript://" + prepared.sessionId() + "/call-1");
        assertThat(store.list(prepared.sessionId(), "initial_context", 20))
                .extracting(ToolTranscriptStore.ToolTranscriptSummary::reference)
                .containsExactly("tool-transcript://" + prepared.sessionId() + "/context-initial");
        assertThat(store.read("tool-transcript://" + prepared.sessionId() + "/context-initial"))
                .get().extracting(ToolTranscriptStore.ToolTranscriptEntry::responseData)
                .asString().contains("complete the task");
        manager.complete();
        assertThat(ToolLoopContextRuntime.drain().signals())
                .containsEntry("tool_transcript_session", prepared.sessionId())
                .containsEntry("tool_initial_context_reference",
                        "tool-transcript://" + prepared.sessionId() + "/context-initial");
    }

    @Test
    void leavesToolMessagesUntouchedWhileBelowBudget() {
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        ToolLoopContextManager manager = new ToolLoopContextManager(
                ToolTranscriptStore.noop(), 10_000, properties);
        Prompt prompt = toolPrompt("small-one", "small-two");

        ToolLoopContextManager.PreparedPrompt prepared = manager.prepare(prompt, true, 2);

        assertThat(prepared.compressionActions()).isZero();
        assertThat(prepared.prompt()).isSameAs(prompt);
        assertThat(ToolLoopContextManager.countTokens(prepared.prompt().getInstructions()))
                .isEqualTo(prepared.originalTokens());
    }

    @Test
    void firstStepRemainsDiscoverableAfterItsExchangeIsRemovedFromWorkingContext() throws Exception {
        ContextEngineeringProperties properties = ContextEngineeringProperties.defaults();
        properties.setCompressedToolResultsMaxChars(100);
        properties.setRecentRawToolRounds(1);
        FileToolTranscriptStore store = new FileToolTranscriptStore(tempDir);
        ToolLoopContextManager manager = new ToolLoopContextManager(store, 400, properties);
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("finish a long task and reuse the first step later"));
        for (int i = 1; i <= 20; i++) {
            String id = "call-" + i;
            messages.add(toolCall(id, "step_tool_" + i));
            String result = i == 1
                    ? "critical early evidence key=FIRST value=retain-this-for-the-final-step"
                    : "intermediate evidence step=" + i + " value=abcdefghijklmnopqrstuvwxyz0123456789";
            messages.add(toolResponse(id, "step_tool_" + i, result));
        }

        ToolLoopContextManager.PreparedPrompt prepared = manager.prepare(new Prompt(messages), true, 20);

        assertThat(prepared.preparedTokens()).isLessThanOrEqualTo(320);
        assertThat(prepared.prompt().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id))
                .doesNotContain("call-1");
        assertThat(prepared.prompt().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText))
                .anyMatch(text -> text.contains("list_archived_tool_results"));
        assertThat(store.list(prepared.sessionId(), "FIRST", 10))
                .extracting(ToolTranscriptStore.ToolTranscriptSummary::reference)
                .contains("tool-transcript://" + prepared.sessionId() + "/call-1");
        assertThat(store.read("tool-transcript://" + prepared.sessionId() + "/call-1"))
                .get().extracting(ToolTranscriptStore.ToolTranscriptEntry::responseData)
                .asString().contains("retain-this-for-the-final-step");
    }

    private static Prompt toolPrompt(String firstResult, String secondResult) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("complete the task"));
        messages.add(toolCall("call-1", "first_tool"));
        messages.add(toolResponse("call-1", "first_tool", firstResult));
        messages.add(toolCall("call-2", "second_tool"));
        messages.add(toolResponse("call-2", "second_tool", secondResult));
        return new Prompt(messages);
    }

    private static AssistantMessage toolCall(String id, String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
    }

    private static ToolResponseMessage toolResponse(String id, String name, String result) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, result)))
                .build();
    }
}
