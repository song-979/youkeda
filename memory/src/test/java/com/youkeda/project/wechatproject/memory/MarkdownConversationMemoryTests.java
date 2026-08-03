package com.youkeda.project.wechatproject.memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownConversationMemoryTests {

    @TempDir
    Path tempDir;

    @Test
    void isCompatibilityAliasForOpenClawMemory() {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        assertThat(memory).isInstanceOf(OpenClawConversationMemory.class);
    }

    @Test
    void keepsShortTermHistoryWithoutWritingOrdinaryChatToDailyMemory() {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "hello", "hi");
        memory.append("user-1", "what did we discuss?", "memory");

        List<MemoryMessage> history = memory.getHistory("user-1");

        assertThat(history).extracting(MemoryMessage::getRole)
                .containsExactly("user", "assistant", "user", "assistant");

        Path dailyFile = tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now() + ".md");
        assertThat(dailyFile).doesNotExist();
        assertThat(tempDir.resolve("user-1").resolve("MEMORY.md")).doesNotExist();
    }

    @Test
    void writesRefinedDailyMemorySections() throws Exception {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "fix login error and remind me tomorrow to verify it",
                "fixed the login error by closing the popup after login");

        Path dailyFile = tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now() + ".md");
        String daily = Files.readString(dailyFile);

        assertThat(daily).contains("# " + LocalDate.now());
        assertThat(daily).contains("OpenClaw daily note");
        assertThat(daily).contains("## Session events");
        assertThat(daily).contains("## Commitments");
        assertThat(daily).contains("request=fix login error and remind me tomorrow to verify it");
        assertThat(tempDir.resolve("user-1").resolve("DREAMS.md")).exists();
    }

    @Test
    void writesSelectedLongTermMemoryToMarkdown() throws Exception {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "\u628a\u201c\u6211\u559c\u6b22\u559d\u5976\u8336\u201d\u5b58\u5165\u957f\u671f\u8bb0\u5fc6", "ok");

        Path memoryFile = tempDir.resolve("user-1").resolve("MEMORY.md");
        assertThat(Files.readString(memoryFile))
                .contains("## Preferences")
                .contains("\u6211\u559c\u6b22\u559d\u5976\u8336");
        assertThat(memory.getHistory("user-1").getFirst().getContent().toString())
                .contains("\u6211\u559c\u6b22\u559d\u5976\u8336");
    }

    @Test
    void supportsCustomDailyMemorySections() throws Exception {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "\u8bb0\u5f55\u5230\u6bcf\u65e5\u8bb0\u5fc6\u7684\u7075\u611f\uff1a\u4e0b\u6b21\u5199 OpenClaw \u6848\u4f8b", "ok");

        Path dailyFile = tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now() + ".md");
        String daily = Files.readString(dailyFile);

        assertThat(daily).contains("## \u7075\u611f");
        assertThat(daily).contains("\u4e0b\u6b21\u5199 OpenClaw \u6848\u4f8b");
    }

    @Test
    void supportsCustomLongTermMemorySections() throws Exception {
        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "\u628a\u201c\u53d1\u5e03\u5e73\u53f0\uff1a\u77e5\u4e4e\u201d\u5b58\u5165\u957f\u671f\u8bb0\u5fc6\u7684\u6e20\u9053\u4fe1\u606f", "ok");

        Path memoryFile = tempDir.resolve("user-1").resolve("MEMORY.md");
        String durableMemory = Files.readString(memoryFile);

        assertThat(durableMemory).contains("## \u6e20\u9053\u4fe1\u606f");
        assertThat(durableMemory).contains("\u53d1\u5e03\u5e73\u53f0\uff1a\u77e5\u4e4e");
    }

    @Test
    void removesExpiredDailyMemoryFiles() throws Exception {
        Path oldFile = tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now().minusDays(30) + ".md");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "# old");

        MarkdownConversationMemory memory = new MarkdownConversationMemory(2, 30, tempDir.toString(), 30);
        memory.append("user-1", "fix login error", "fixed login error");

        assertThat(oldFile).doesNotExist();
    }
}
