package com.youkeda.project.wechatproject.bot.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VectorMemoryIndexTests {

    @TempDir
    Path tempDir;

    @Test
    void lshBandsAreDeterministicAndSeparateOppositeVectors() {
        double[] vector = {0.2, -0.5, 1.3, 0.7, -0.1};
        double[] opposite = {-0.2, 0.5, -1.3, -0.7, 0.1};

        int[] first = VectorMemoryIndex.lshBands(vector);
        int[] second = VectorMemoryIndex.lshBands(vector.clone());
        int[] reversed = VectorMemoryIndex.lshBands(opposite);

        assertThat(first).containsExactly(second);
        assertThat(reversed).isNotEqualTo(first);
        for (int i = 0; i < first.length; i++) {
            assertThat(first[i] ^ reversed[i]).isEqualTo(15);
        }
    }

    @Test
    void persistsLshBandsAndRetrievesIndexedMemory() throws Exception {
        Path database = tempDir.resolve("memory.db");
        VectorMemoryIndex index = new VectorMemoryIndex(database,
                text -> text.contains("alpha") ? new double[]{1, 0, 0, 0} : new double[]{0, 1, 0, 0},
                300, 0, 3, 0.1, "test-model");

        String result = index.retrieve("user", "alpha", List.of(
                new VectorMemoryIndex.SourceDocument("MEMORY.md", "durable", 1L,
                        "# Facts\nalpha is the remembered value")));

        assertThat(result).contains("alpha is the remembered value");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT lsh_band0, lsh_band1, lsh_band2, lsh_band3 FROM memory_index_embeddings")) {
            assertThat(rows.next()).isTrue();
            for (int i = 1; i <= 4; i++) {
                rows.getInt(i);
                assertThat(rows.wasNull()).isFalse();
            }
        }
    }

    @Test
    void embeddingFailureUsesLexicalFallbackAndOpensCooldown() throws Exception {
        AtomicInteger embeddingCalls = new AtomicInteger();
        VectorMemoryIndex index = new VectorMemoryIndex(tempDir.resolve("fallback.db"), text -> {
            embeddingCalls.incrementAndGet();
            throw new java.io.IOException("embedding provider unavailable");
        }, 300, 0, 3, 0.1, "missing-model");
        List<VectorMemoryIndex.SourceDocument> documents = List.of(
                new VectorMemoryIndex.SourceDocument("MEMORY.md", "durable", 1L,
                        "# Facts\nalpha is available through lexical fallback"));

        String first = index.retrieve("user", "alpha", documents);
        String second = index.retrieve("user", "alpha", documents);

        assertThat(first).contains("alpha is available through lexical fallback");
        assertThat(second).contains("alpha is available through lexical fallback");
        assertThat(embeddingCalls).hasValue(1);
    }
}
