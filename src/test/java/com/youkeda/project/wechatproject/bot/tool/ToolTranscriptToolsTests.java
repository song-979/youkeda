package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.context.FileToolTranscriptStore;
import com.youkeda.project.wechatproject.bot.context.ToolTranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTranscriptToolsTests {

    @TempDir
    Path tempDir;

    @Test
    void discoversEarlyResultsThenReadsThemInTokenPages() throws Exception {
        FileToolTranscriptStore store = new FileToolTranscriptStore(tempDir);
        store.append(new ToolTranscriptStore.ToolTranscriptEntry(
                "long-task", 1, "first-step", "web_search",
                "critical-first-step-value " + "detail item ".repeat(2_000)));
        ToolTranscriptTools tools = new ToolTranscriptTools(store);

        String index = tools.listArchivedToolResults("long-task", "critical-first-step", 10);
        String firstPage = tools.readArchivedToolResult(
                "tool-transcript://long-task/first-step", 0, 100);
        String secondPage = tools.readArchivedToolResult(
                "tool-transcript://long-task/first-step", 100, 100);

        assertThat(index).contains("first-step", "web_search", "critical-first-step-value");
        assertThat(firstPage).contains("hasMore=true", "nextOffsetToken=100", "critical-first-step-value");
        assertThat(secondPage).contains("offsetToken=100").doesNotContain("critical-first-step-value");
    }
}
