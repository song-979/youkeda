package com.youkeda.project.wechatproject.bot.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolTranscriptStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void listsSearchableSummariesWithoutLoadingResultsIntoThePrompt() throws Exception {
        FileToolTranscriptStore store = new FileToolTranscriptStore(tempDir);
        store.append(new ToolTranscriptStore.ToolTranscriptEntry(
                "session-1", 1, "call-1", "web_search", "weather in Shenzhen is sunny"));
        store.append(new ToolTranscriptStore.ToolTranscriptEntry(
                "session-1", 2, "call-2", "route", "train route to Guangzhou"));

        assertThat(store.list("session-1", "weather", 10))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.reference()).isEqualTo("tool-transcript://session-1/call-1");
                    assertThat(summary.toolName()).isEqualTo("web_search");
                    assertThat(summary.preview()).contains("Shenzhen");
                });
    }

    @Test
    void removesTranscriptFilesOlderThanTheCutoff() throws Exception {
        FileToolTranscriptStore store = new FileToolTranscriptStore(tempDir);
        store.append(new ToolTranscriptStore.ToolTranscriptEntry(
                "expired-session", 1, "call-1", "tool", "old result"));
        Path transcript = tempDir.resolve("expired-session.jsonl");
        Files.setLastModifiedTime(transcript, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

        int removed = store.cleanupExpired(Instant.parse("2021-01-01T00:00:00Z"));

        assertThat(removed).isEqualTo(1);
        assertThat(transcript).doesNotExist();
    }
}
