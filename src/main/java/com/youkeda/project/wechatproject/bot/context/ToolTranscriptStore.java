package com.youkeda.project.wechatproject.bot.context;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable store for the full, uncompressed results produced inside one tool loop. */
public interface ToolTranscriptStore {

    String append(ToolTranscriptEntry entry) throws IOException;

    Optional<ToolTranscriptEntry> read(String reference) throws IOException;

    List<ToolTranscriptSummary> list(String sessionId, String query, int limit) throws IOException;

    int cleanupExpired(Instant cutoff) throws IOException;

    record ToolTranscriptEntry(
            String sessionId,
            int modelRound,
            String toolCallId,
            String toolName,
            String responseData) {
    }

    static ToolTranscriptStore noop() {
        return new ToolTranscriptStore() {
            @Override
            public String append(ToolTranscriptEntry entry) {
                return "tool-transcript://" + entry.sessionId() + "/" + entry.toolCallId();
            }

            @Override
            public Optional<ToolTranscriptEntry> read(String reference) {
                return Optional.empty();
            }

            @Override
            public List<ToolTranscriptSummary> list(String sessionId, String query, int limit) {
                return List.of();
            }

            @Override
            public int cleanupExpired(Instant cutoff) {
                return 0;
            }
        };
    }

    record ToolTranscriptSummary(
            String reference,
            int modelRound,
            String toolName,
            int tokenCount,
            String preview) {
    }
}
