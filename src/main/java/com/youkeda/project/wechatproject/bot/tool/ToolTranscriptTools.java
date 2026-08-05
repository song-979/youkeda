package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.context.ToolTranscriptStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.List;

/** Allows an agent to retrieve a full result that was archived during its tool loop. */
public class ToolTranscriptTools implements ToolService.ProjectTool {

    private static final int MAX_RETRIEVED_TOKENS = 5_000;

    private final ToolTranscriptStore store;

    public ToolTranscriptTools(ToolTranscriptStore store) {
        this.store = store;
    }

    @Override
    public String category() {
        return "information";
    }

    @Tool(name = "read_archived_tool_result",
            description = "Read one token page from an archived tool result or initial context. "
                    + "Use nextOffsetToken repeatedly while hasMore=true; do not reload the whole result.")
    public String readArchivedToolResult(
            @ToolParam(description = "Reference beginning with tool-transcript://") String reference,
            @ToolParam(required = false, description = "Token offset, starting at 0") Integer offsetToken,
            @ToolParam(required = false, description = "Page size in tokens, 100-5000") Integer maxTokens) {
        try {
            return store.read(reference)
                    .map(ToolTranscriptStore.ToolTranscriptEntry::responseData)
                    .map(value -> page(reference, value, offsetToken, maxTokens))
                    .orElse("Archived tool result was not found: " + reference);
        } catch (IOException e) {
            return "Failed to read archived tool result: " + e.getMessage();
        }
    }

    @Tool(name = "list_archived_tool_results",
            description = "List or search archived early tool results and the initial context for one tool-loop "
                    + "session. Use this when an old exchange was removed and its exact reference is no longer visible.")
    public String listArchivedToolResults(
            @ToolParam(description = "Session id shown in the archived-tool notice") String sessionId,
            @ToolParam(required = false, description = "Optional keyword to search tool names and result content")
            String query,
            @ToolParam(required = false, description = "Maximum summaries to return, 1-50") Integer limit) {
        try {
            List<ToolTranscriptStore.ToolTranscriptSummary> results = store.list(
                    sessionId, query, limit != null ? limit : 20);
            if (results.isEmpty()) {
                return "No archived tool results found for session=" + sessionId;
            }
            StringBuilder output = new StringBuilder("Archived entries for session=")
                    .append(sessionId).append(':').append('\n');
            for (ToolTranscriptStore.ToolTranscriptSummary result : results) {
                output.append("- round=").append(result.modelRound())
                        .append(" tool=").append(result.toolName())
                        .append(" tokens=").append(result.tokenCount())
                        .append(" reference=").append(result.reference()).append('\n')
                        .append("  preview=").append(result.preview()).append('\n');
            }
            return output.toString().stripTrailing();
        } catch (IOException e) {
            return "Failed to list archived tool results: " + e.getMessage();
        }
    }

    private static String page(String reference, String value, Integer offsetToken, Integer maxTokens) {
        int offset = offsetToken != null ? Math.max(0, offsetToken) : 0;
        int limit = maxTokens != null ? Math.max(100, Math.min(MAX_RETRIEVED_TOKENS, maxTokens))
                : MAX_RETRIEVED_TOKENS;
        TokenBudgetUtil.TokenSlice slice = TokenBudgetUtil.sliceByTokens(value, offset, limit);
        return "reference=" + reference + "\n"
                + "offsetToken=" + offset + "\n"
                + "nextOffsetToken=" + slice.nextOffsetToken() + "\n"
                + "totalTokens=" + slice.totalTokens() + "\n"
                + "hasMore=" + slice.hasMore() + "\n"
                + "content:\n" + slice.content();
    }
}
