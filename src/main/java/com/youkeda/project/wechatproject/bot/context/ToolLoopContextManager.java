package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Applies the shared 80% context budget to messages accumulated by Spring AI's tool loop. */
public class ToolLoopContextManager {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopContextManager.class);

    private final ToolTranscriptStore transcriptStore;
    private final int inputTokenLimit;
    private final int recentRawToolRounds;
    private final int compressedResultMaxChars;
    private final ThreadLocal<SessionState> sessions = new ThreadLocal<>();

    public ToolLoopContextManager(ToolTranscriptStore transcriptStore,
                                  int contextWindowTokens,
                                  ContextEngineeringProperties properties) {
        ContextEngineeringProperties safe = properties != null
                ? properties : ContextEngineeringProperties.defaults();
        this.transcriptStore = transcriptStore != null ? transcriptStore : ToolTranscriptStore.noop();
        this.inputTokenLimit = safe.toBudget(contextWindowTokens).inputTokenLimit();
        this.recentRawToolRounds = safe.getRecentRawToolRounds();
        this.compressedResultMaxChars = safe.getCompressedToolResultsMaxChars();
    }

    public PreparedPrompt prepare(Prompt prompt, boolean continuation, int modelRound) {
        SessionState session = sessions.get();
        if (!continuation || session == null) {
            session = new SessionState(UUID.randomUUID().toString());
            sessions.set(session);
            persistInitialContext(prompt, session);
        }
        persistNewResults(prompt, modelRound, session);

        int originalTokens = countTokens(prompt.getInstructions());
        if (originalTokens <= inputTokenLimit) {
            return new PreparedPrompt(prompt, originalTokens, originalTokens, 0, session.sessionId);
        }

        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        List<Integer> responseIndexes = toolResponseIndexes(messages);
        int compactedResults = 0;
        int protectedFrom = Math.max(0, responseIndexes.size() - recentRawToolRounds);

        for (int i = 0; i < protectedFrom && tokens(messages) > inputTokenLimit; i++) {
            if (compactResponse(messages, responseIndexes.get(i), session)) {
                compactedResults++;
            }
        }
        for (int i = protectedFrom; i < responseIndexes.size() - 1
                && tokens(messages) > inputTokenLimit; i++) {
            if (compactResponse(messages, responseIndexes.get(i), session)) {
                compactedResults++;
            }
        }
        if (!responseIndexes.isEmpty() && tokens(messages) > inputTokenLimit) {
            if (compactResponse(messages, responseIndexes.getLast(), session)) {
                compactedResults++;
            }
        }
        for (int i = 0; i < messages.size() && tokens(messages) > inputTokenLimit; i++) {
            if (compactToolCallArguments(messages, i, session)) {
                compactedResults++;
            }
        }

        int removedExchanges = 0;
        if (tokens(messages) > inputTokenLimit && countToolExchanges(messages) > 1) {
            insertArchiveNotice(messages, session.sessionId);
        }
        while (tokens(messages) > inputTokenLimit && countToolExchanges(messages) > 1) {
            if (!removeOldestToolExchange(messages)) {
                break;
            }
            removedExchanges++;
        }

        Prompt prepared = new Prompt(messages, prompt.getOptions());
        int preparedTokens = countTokens(prepared.getInstructions());
        log.info("tool context compressed for session {}: {} -> {} tokens, results={}, exchanges={}",
                session.sessionId, originalTokens, preparedTokens, compactedResults, removedExchanges);
        return new PreparedPrompt(prepared, originalTokens, preparedTokens,
                compactedResults + removedExchanges, session.sessionId);
    }

    public void complete() {
        SessionState session = sessions.get();
        if (session != null) {
            ToolLoopContextRuntime.publish(new ToolLoopContextRuntime.Report(
                    session.sessionId, session.references.get("context-initial")));
        }
        sessions.remove();
    }

    private void persistNewResults(Prompt prompt, int modelRound, SessionState session) {
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    String arguments = Objects.toString(call.arguments(), "");
                    String key = "arguments:" + call.id() + ":" + Integer.toHexString(arguments.hashCode());
                    if (!session.persistedKeys.add(key)) {
                        continue;
                    }
                    try {
                        String reference = transcriptStore.append(new ToolTranscriptStore.ToolTranscriptEntry(
                                session.sessionId, modelRound, call.id() + "-arguments",
                                "tool_call_arguments:" + call.name(), arguments));
                        session.argumentReferences.put(call.id(), reference);
                    } catch (IOException e) {
                        log.warn("failed to persist tool-call arguments {}: {}", call.id(), e.getMessage());
                    }
                }
            }
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                String responseData = Objects.toString(response.responseData(), "");
                String key = response.id() + ":" + Integer.toHexString(responseData.hashCode());
                if (!session.persistedKeys.add(key)) {
                    continue;
                }
                try {
                    String reference = transcriptStore.append(new ToolTranscriptStore.ToolTranscriptEntry(
                            session.sessionId, modelRound, response.id(), response.name(), responseData));
                    session.references.put(response.id(), reference);
                } catch (IOException e) {
                    log.warn("failed to persist tool result {}: {}", response.id(), e.getMessage());
                }
            }
        }
    }

    private void persistInitialContext(Prompt prompt, SessionState session) {
        StringBuilder snapshot = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            snapshot.append('[').append(message.getMessageType()).append("]\n");
            if (message instanceof ToolResponseMessage) {
                snapshot.append("[tool response archived as a separate indexed entry]\n\n");
                continue;
            }
            if (message.getText() != null && !message.getText().isBlank()) {
                snapshot.append(message.getText()).append('\n');
            }
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    snapshot.append("tool_call name=").append(call.name())
                            .append(" id=").append(call.id())
                            .append(" arguments=").append(call.arguments()).append('\n');
                }
            }
            snapshot.append('\n');
        }
        try {
            String reference = transcriptStore.append(new ToolTranscriptStore.ToolTranscriptEntry(
                    session.sessionId, 0, "context-initial", "initial_context", snapshot.toString()));
            session.references.put("context-initial", reference);
            session.persistedKeys.add("context-initial");
        } catch (IOException e) {
            log.warn("failed to persist initial tool-loop context: {}", e.getMessage());
        }
    }

    private boolean compactResponse(List<Message> messages, int index, SessionState session) {
        if (index < 0 || index >= messages.size()
                || !(messages.get(index) instanceof ToolResponseMessage original)) {
            return false;
        }
        List<ToolResponseMessage.ToolResponse> compacted = original.getResponses().stream()
                .map(response -> new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), compactResult(response, session)))
                .toList();
        ToolResponseMessage replacement = ToolResponseMessage.builder()
                .responses(compacted)
                .metadata(original.getMetadata())
                .build();
        if (responseLength(replacement) >= responseLength(original)) {
            return false;
        }
        messages.set(index, replacement);
        return true;
    }

    private String compactResult(ToolResponseMessage.ToolResponse response, SessionState session) {
        String data = response.responseData() != null ? response.responseData() : "";
        String reference = session.references.getOrDefault(response.id(),
                "tool-transcript://" + session.sessionId + "/" + response.id());
        if (data.length() <= compressedResultMaxChars) {
            return data;
        }
        int markerBudget = Math.min(160, compressedResultMaxChars / 3);
        int contentBudget = Math.max(80, compressedResultMaxChars - markerBudget);
        int headLength = Math.max(1, (int) (contentBudget * 0.7));
        int tailLength = Math.max(1, contentBudget - headLength);
        String preview = data.substring(0, Math.min(headLength, data.length())).stripTrailing();
        String tail = data.substring(Math.max(0, data.length() - tailLength)).stripLeading();
        return "[archived full result: " + reference + "]\n"
                + "If required details are missing, call read_archived_tool_result with this reference.\n"
                + preview + "\n...[compressed]...\n" + tail;
    }

    private boolean compactToolCallArguments(List<Message> messages, int index, SessionState session) {
        if (index < 0 || index + 1 >= messages.size()
                || !(messages.get(index) instanceof AssistantMessage original)
                || !original.hasToolCalls()
                || !(messages.get(index + 1) instanceof ToolResponseMessage)) {
            return false;
        }
        boolean changed = original.getToolCalls().stream()
                .anyMatch(call -> call.arguments() != null && call.arguments().length() > compressedResultMaxChars);
        if (!changed) {
            return false;
        }
        List<AssistantMessage.ToolCall> compactedCalls = original.getToolCalls().stream()
                .map(call -> {
                    String reference = session.argumentReferences.getOrDefault(call.id(),
                            "tool-transcript://" + session.sessionId + "/" + call.id() + "-arguments");
                    String arguments = call.arguments() != null && call.arguments().length() > compressedResultMaxChars
                            ? "{\"archivedArguments\":true,\"resultReference\":\"" + reference + "\"}"
                            : call.arguments();
                    return new AssistantMessage.ToolCall(
                            call.id(), call.type(), call.name(), arguments);
                })
                .toList();
        AssistantMessage replacement = AssistantMessage.builder()
                .content(Objects.toString(original.getText(), ""))
                .properties(original.getMetadata())
                .toolCalls(compactedCalls)
                .media(original.getMedia())
                .build();
        messages.set(index, replacement);
        return true;
    }

    private static List<Integer> toolResponseIndexes(List<Message> messages) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static int tokens(List<Message> messages) {
        return countTokens(messages);
    }

    static int countTokens(List<Message> messages) {
        int tokens = 0;
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    tokens += TokenBudgetUtil.countTokens(response.id());
                    tokens += TokenBudgetUtil.countTokens(response.name());
                    tokens += TokenBudgetUtil.countTokens(response.responseData());
                }
            } else if (message instanceof AssistantMessage assistant) {
                tokens += TokenBudgetUtil.countTokens(assistant.getText());
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    tokens += TokenBudgetUtil.countTokens(call.id());
                    tokens += TokenBudgetUtil.countTokens(call.name());
                    tokens += TokenBudgetUtil.countTokens(call.arguments());
                }
            } else {
                tokens += TokenBudgetUtil.countTokens(message.getText());
            }
        }
        return tokens;
    }

    private static int responseLength(ToolResponseMessage message) {
        return message.getResponses().stream()
                .mapToInt(response -> Objects.toString(response.responseData(), "").length())
                .sum();
    }

    private static int countToolExchanges(List<Message> messages) {
        int count = 0;
        for (int i = 0; i + 1 < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()
                    && messages.get(i + 1) instanceof ToolResponseMessage) {
                count++;
            }
        }
        return count;
    }

    private static boolean removeOldestToolExchange(List<Message> messages) {
        for (int i = 0; i + 1 < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()
                    && messages.get(i + 1) instanceof ToolResponseMessage) {
                messages.remove(i + 1);
                messages.remove(i);
                return true;
            }
        }
        return false;
    }

    private static void insertArchiveNotice(List<Message> messages, String sessionId) {
        int index = 0;
        while (index < messages.size() && messages.get(index) instanceof SystemMessage) {
            index++;
        }
        messages.add(index, new SystemMessage("Earlier tool exchanges were archived and removed from the working "
                + "context to preserve the token budget. session=" + sessionId
                + ". Call list_archived_tool_results for this session to discover early results or the initial "
                + "context, then call read_archived_tool_result with pagination when details are needed. "
                + "Do this before repeating an old tool call or claiming that early evidence is unavailable."));
    }

    public record PreparedPrompt(
            Prompt prompt,
            int originalTokens,
            int preparedTokens,
            int compressionActions,
            String sessionId) {
    }

    private static final class SessionState {
        private final String sessionId;
        private final Set<String> persistedKeys = new LinkedHashSet<>();
        private final Map<String, String> references = new HashMap<>();
        private final Map<String, String> argumentReferences = new HashMap<>();

        private SessionState(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
