package com.youkeda.project.wechatproject.memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawConversationMemoryTests {

    @TempDir
    Path tempDir;

    @Test
    void keepsWorkingMemoryWithoutInjectingEmptyBootstrapContext() {
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "hello", "hi");
        memory.append("user-1", "what did we discuss?", "memory");

        List<MemoryMessage> history = memory.getHistory("user-1");

        assertThat(history).extracting(MemoryMessage::getRole)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(tempDir.resolve("user-1").resolve("MEMORY.md")).doesNotExist();
        assertThat(tempDir.resolve("user-1").resolve("DREAMS.md")).doesNotExist();
        assertThat(tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now() + ".md")).doesNotExist();
    }

    @Test
    void writesMemoryDailyAndDreamLayers() throws Exception {
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "fix login error and remind me tomorrow to verify it",
                "fixed the login error by closing the popup after login");
        memory.append("user-1", "\u628a\u201c\u6211\u559c\u6b22\u559d\u5976\u8336\u201d\u5b58\u5165\u957f\u671f\u8bb0\u5fc6", "ok");

        Path userDir = tempDir.resolve("user-1");
        String daily = Files.readString(userDir.resolve("memory").resolve(LocalDate.now() + ".md"));
        String durable = Files.readString(userDir.resolve("MEMORY.md"));
        String dreams = Files.readString(userDir.resolve("DREAMS.md"));

        assertThat(daily)
                .contains("OpenClaw daily note")
                .contains("## Session events")
                .contains("## Commitments")
                .contains("request=fix login error and remind me tomorrow to verify it");
        assertThat(durable)
                .contains("# MEMORY.md")
                .contains("## Preferences")
                .contains("\u6211\u559c\u6b22\u559d\u5976\u8336");
        assertThat(dreams)
                .contains("# DREAMS.md")
                .contains("fix login error");
        assertThat(memory.getHistory("user-1").getFirst().getContent().toString())
                .contains("MEMORY.md - durable curated memory")
                .contains("DREAMS.md - review candidates and consolidation hints")
                .contains("memory/*.md - retained daily notes")
                .contains("fix login error")
                .contains("\u6211\u559c\u6b22\u559d\u5976\u8336");
    }

    @Test
    void readsOpenClawMemoryMdFile() throws Exception {
        Path memoryFile = tempDir.resolve("user-1").resolve("MEMORY.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, "## compatibility\n- prefers concise replies");

        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        assertThat(memory.getHistory("user-1").getFirst().getContent().toString())
                .contains("prefers concise replies");
    }

    @Test
    void readsLegacyLongTermFileWhenMemoryMdIsMissing() throws Exception {
        Path legacyFile = tempDir.resolve("user-1").resolve("long-term.md");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "## compatibility\n- prefers concise replies");

        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        assertThat(memory.getHistory("user-1").getFirst().getContent().toString())
                .contains("prefers concise replies");
    }

    @Test
    void removesExpiredDailyMemoryFiles() throws Exception {
        Path oldFile = tempDir.resolve("user-1").resolve("memory").resolve(LocalDate.now().minusDays(30) + ".md");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "# old\n- expired");

        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);
        memory.append("user-1", "fix login error", "fixed login error");

        assertThat(oldFile).doesNotExist();
    }

    @Test
    void removesExpiredWeeklySummaryFilesWithoutFailingOnWeekNames() throws Exception {
        LocalDate oldWeek = LocalDate.now().minusWeeks(13);
        String oldWeekName = oldWeek.get(WeekFields.ISO.weekBasedYear())
                + "-W" + String.format("%02d", oldWeek.get(WeekFields.ISO.weekOfWeekBasedYear())) + ".md";
        Path oldFile = tempDir.resolve("user-1").resolve("weekly").resolve(oldWeekName);
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "# old weekly\n- expired");

        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        memory.getHistory("user-1");

        assertThat(oldFile).doesNotExist();
    }

    @Test
    void dreamSweepWritesCandidatesToDreamsWithoutPromotingToMemory() throws Exception {
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        memory.append("user-1", "\u6211\u559c\u6b22\u7b80\u6d01\u7684\u56de\u590d", "noted");
        memory.dream("user-1");

        Path userDir = tempDir.resolve("user-1");
        assertThat(Files.readString(userDir.resolve("DREAMS.md")))
                .contains("\u6211\u559c\u6b22\u7b80\u6d01\u7684\u56de\u590d");
        assertThat(userDir.resolve("MEMORY.md")).doesNotExist();
    }

    @Test
    void bootstrapLoadsDreamsAndRetainedDailyNotes() throws Exception {
        Path userDir = tempDir.resolve("user-1");
        Path dailyFile = userDir.resolve("memory").resolve(LocalDate.now().minusDays(2) + ".md");
        Files.createDirectories(dailyFile.getParent());
        Files.writeString(dailyFile, "# old daily\n- retained daily note");
        Files.writeString(userDir.resolve("DREAMS.md"), "# DREAMS.md\n- candidate dream note");

        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30);

        assertThat(memory.getHistory("user-1").getFirst().getContent().toString())
                .contains("candidate dream note")
                .contains("retained daily note");
    }

    @Test
    void vectorIndexReturnsRelevantMarkdownChunksOnly() throws Exception {
        Path userDir = tempDir.resolve("user-1");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), """
                # MEMORY.md

                ## Preferences
                - prefers concise replies

                ## Projects
                - project Atlas uses SQLite vector retrieval for Markdown memory chunks
                """);

        VectorMemoryIndex index = new VectorMemoryIndex(
                tempDir.resolve("memory-index.sqlite"),
                OpenClawConversationMemoryTests::fakeEmbedding,
                240,
                0,
                1,
                0.1d);
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30, index);

        String bootstrap = memory.getHistory("user-1", "Atlas 的 memory 检索怎么做？")
                .getFirst()
                .getContent()
                .toString();

        assertThat(bootstrap)
                .contains("SQLite vector retrieval")
                .doesNotContain("prefers concise replies");
        assertThat(tempDir.resolve("memory-index.sqlite")).exists();
    }

    @Test
    void vectorIndexMergesVectorAndBm25HitsWithRrf() throws Exception {
        Path userDir = tempDir.resolve("user-1");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), """
                # MEMORY.md

                ## Projects
                - project Atlas uses SQLite vector retrieval for Markdown memory chunks

                ## Deadlines
                - passport renewal deadline is Friday
                """);

        VectorMemoryIndex index = new VectorMemoryIndex(
                tempDir.resolve("memory-index.sqlite"),
                OpenClawConversationMemoryTests::fakeEmbedding,
                240,
                0,
                2,
                0.1d);
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30, index);

        String bootstrap = memory.getHistory("user-1", "Atlas deadline")
                .getFirst()
                .getContent()
                .toString();

        assertThat(bootstrap)
                .contains("SQLite vector retrieval")
                .contains("passport renewal deadline is Friday");
    }

    @Test
    void vectorIndexFallsBackToLexicalSearchWhenEmbeddingUnavailable() throws Exception {
        Path userDir = tempDir.resolve("user-1");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), """
                # MEMORY.md

                ## Projects
                - project Atlas uses SQLite vector retrieval for Markdown memory chunks
                """);

        VectorMemoryIndex index = new VectorMemoryIndex(
                tempDir.resolve("memory-index.sqlite"),
                ignored -> {
                    throw new IOException("embedding unavailable");
                },
                240,
                0,
                1,
                0.1d);
        OpenClawConversationMemory memory = new OpenClawConversationMemory(2, 30, tempDir.toString(), 30, index);

        String bootstrap = memory.getHistory("user-1", "Atlas memory")
                .getFirst()
                .getContent()
                .toString();

        assertThat(bootstrap).contains("project Atlas uses SQLite vector retrieval");
    }

    private static double[] fakeEmbedding(String text) {
        String normalized = text == null ? "" : text.toLowerCase();
        return new double[] {
                containsAny(normalized, "atlas", "sqlite", "vector", "向量", "检索") ? 1.0d : 0.0d,
                containsAny(normalized, "concise", "简洁") ? 1.0d : 0.0d
        };
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
