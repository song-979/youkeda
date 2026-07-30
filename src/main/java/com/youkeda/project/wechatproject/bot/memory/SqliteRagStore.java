package com.youkeda.project.wechatproject.bot.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SQLite-backed implementation of {@link RagStore}.
 *
 * <p>Stores document chunks with vector embeddings in the same SQLite database
 * used by {@link VectorMemoryIndex}, under separate {@code rag_*} tables.
 * Supports namespace isolation within each user for organizing different
 * knowledge domains (work, study, family, etc.).
 */
public class SqliteRagStore implements RagStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteRagStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Double>> DOUBLE_LIST_TYPE = new TypeReference<>() {
    };

    private final Path dbPath;
    private final EmbeddingClient embeddingClient;
    private final int chunkChars;
    private final int overlapChars;
    private final int topK;
    private final double minScore;
    private final String embeddingModel;

    public SqliteRagStore(Path dbPath, EmbeddingClient embeddingClient,
                          int chunkChars, int overlapChars, int topK, double minScore,
                          String embeddingModel) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null").toAbsolutePath().normalize();
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
        this.chunkChars = Math.max(240, chunkChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.chunkChars / 3));
        this.topK = Math.max(1, topK);
        this.minScore = minScore;
        this.embeddingModel = isBlank(embeddingModel) ? "unknown" : embeddingModel;
        try {
            Files.createDirectories(this.dbPath.getParent());
            initialize();
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("failed to initialize RAG store: " + this.dbPath, e);
        }
    }

    // ── RagStore API ────────────────────────────────────────────

    @Override
    public void index(String userId, String namespace, String docId, String content) {
        if (isBlank(userId) || isBlank(namespace) || isBlank(docId) || isBlank(content)) {
            log.debug("skipping index: empty userId/namespace/docId/content");
            return;
        }
        try {
            delete(userId, namespace, docId);
            List<TextChunk> chunks = splitText(content);
            if (chunks.isEmpty()) {
                log.debug("no chunks produced for docId={}", docId);
                return;
            }
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try {
                    long sourceId = insertSource(conn, userId, namespace, docId, content, chunks.size());
                    insertChunks(conn, sourceId, chunks);
                    conn.commit();
                } catch (SQLException | IOException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            log.info("indexed docId={} for userId={}, namespace={}, chunks={}",
                    docId, userId, namespace, chunks.size());
        } catch (SQLException | IOException e) {
            log.error("failed to index docId={} for userId={}, namespace={}: {}",
                    docId, userId, namespace, e.getMessage(), e);
        }
    }

    @Override
    public List<RagChunk> retrieve(String userId, String namespace, String query, int topK) {
        if (isBlank(userId) || isBlank(namespace) || isBlank(query)) {
            return List.of();
        }
        int k = Math.max(1, Math.min(topK, this.topK));
        try {
            double[] queryEmbedding = embeddingClient.embed(query);
            List<ScoredChunk> hits = new ArrayList<>();
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT s.doc_id, c.content, e.embedding_json
                         FROM rag_chunks c
                         JOIN rag_sources s ON s.id = c.source_id
                         JOIN rag_embeddings e ON e.chunk_id = c.id
                         WHERE s.user_id = ? AND s.namespace = ?
                         """)) {
                ps.setString(1, userId);
                ps.setString(2, namespace);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double[] embedding = parseEmbedding(rs.getString("embedding_json"));
                        double score = cosine(queryEmbedding, embedding);
                        if (score >= minScore) {
                            hits.add(new ScoredChunk(rs.getString("doc_id"), rs.getString("content"), score));
                        }
                    }
                }
            }
            hits.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                    .thenComparing(ScoredChunk::docId));
            if (hits.size() > k) {
                hits = hits.subList(0, k);
            }
            log.debug("rag retrieve userId={}, namespace={}, queryLen={}, hits={}",
                    userId, namespace, query.length(), hits.size());
            return hits.stream()
                    .map(h -> new RagChunk(h.docId(), h.content(), h.score()))
                    .toList();
        } catch (IOException | SQLException e) {
            log.error("rag retrieve failed for userId={}, namespace={}: {}",
                    userId, namespace, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public void delete(String userId, String namespace, String docId) {
        if (isBlank(userId) || isBlank(namespace) || isBlank(docId)) {
            return;
        }
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM rag_sources WHERE user_id = ? AND namespace = ? AND doc_id = ?")) {
                ps.setString(1, userId);
                ps.setString(2, namespace);
                ps.setString(3, docId);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    log.info("deleted rag doc docId={} from namespace={} for userId={}", docId, namespace, userId);
                }
            }
        } catch (SQLException e) {
            log.error("failed to delete docId={} for userId={}, namespace={}: {}",
                    docId, userId, namespace, e.getMessage(), e);
        }
    }

    @Override
    public void deleteNamespace(String userId, String namespace) {
        if (isBlank(userId) || isBlank(namespace)) {
            return;
        }
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM rag_sources WHERE user_id = ? AND namespace = ?")) {
                ps.setString(1, userId);
                ps.setString(2, namespace);
                int deleted = ps.executeUpdate();
                log.info("deleted rag namespace={} for userId={}, {} docs removed", namespace, userId, deleted);
            }
        } catch (SQLException e) {
            log.error("failed to delete namespace={} for userId={}: {}",
                    namespace, userId, e.getMessage(), e);
        }
    }

    // ── schema ─────────────────────────────────────────────────

    private void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS rag_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id TEXT NOT NULL,
                        namespace TEXT NOT NULL,
                        doc_id TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        chunk_count INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        UNIQUE(user_id, namespace, doc_id)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS rag_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_id INTEGER NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        UNIQUE(source_id, chunk_index),
                        FOREIGN KEY(source_id) REFERENCES rag_sources(id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS rag_embeddings (
                        chunk_id INTEGER PRIMARY KEY,
                        embedding_model TEXT NOT NULL,
                        dimensions INTEGER NOT NULL,
                        embedding_json TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(chunk_id) REFERENCES rag_chunks(id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rag_sources_user_ns ON rag_sources(user_id, namespace)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunks_source ON rag_chunks(source_id)");
        }
    }

    // ── chunking ───────────────────────────────────────────────

    /**
     * Split plain text into overlapping chunks at paragraph boundaries.
     */
    private List<TextChunk> splitText(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\R\\s*\\R", -1);
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String candidate = trimmed + "\n\n";
            if (!current.isEmpty() && current.length() + candidate.length() > chunkChars) {
                chunks.add(new TextChunk(index++, current.toString().trim()));
                String tail = overlapTail(current.toString());
                current.setLength(0);
                if (!tail.isBlank()) {
                    current.append(tail).append("\n\n");
                }
            }
            current.append(candidate);
        }
        if (!current.isEmpty()) {
            String last = current.toString().trim();
            if (!last.isBlank()) {
                chunks.add(new TextChunk(index, last));
            }
        }
        return chunks;
    }

    private String overlapTail(String text) {
        if (overlapChars <= 0 || text == null || text.length() <= overlapChars) {
            return "";
        }
        int start = text.length() - overlapChars;
        int nextSpace = text.indexOf(' ', start);
        if (nextSpace > start && nextSpace < text.length()) {
            return text.substring(nextSpace).trim();
        }
        int prevSpace = text.lastIndexOf(' ', start);
        if (prevSpace > 0) {
            return text.substring(prevSpace).trim();
        }
        return text.substring(start).trim();
    }

    // ── DB helpers ─────────────────────────────────────────────

    private long insertSource(Connection conn, String userId, String namespace,
                               String docId, String content, int chunkCount)
            throws SQLException, IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO rag_sources (
                    user_id, namespace, doc_id, content_hash, chunk_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, userId);
            ps.setString(2, namespace);
            ps.setString(3, docId);
            ps.setString(4, sha256(content));
            ps.setInt(5, chunkCount);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM rag_sources WHERE user_id = ? AND namespace = ? AND doc_id = ?")) {
            ps.setString(1, userId);
            ps.setString(2, namespace);
            ps.setString(3, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("failed to resolve rag source id for " + docId);
    }

    private void insertChunks(Connection conn, long sourceId, List<TextChunk> chunks)
            throws SQLException, IOException {
        String chunkSql = """
                INSERT INTO rag_chunks (source_id, chunk_index, content, created_at)
                VALUES (?, ?, ?, ?)
                """;
        String embeddingSql = """
                INSERT INTO rag_embeddings (chunk_id, embedding_model, dimensions, embedding_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement chunkPs = conn.prepareStatement(chunkSql);
             PreparedStatement embeddingPs = conn.prepareStatement(embeddingSql)) {
            for (TextChunk chunk : chunks) {
                chunkPs.setLong(1, sourceId);
                chunkPs.setInt(2, chunk.index());
                chunkPs.setString(3, chunk.content());
                chunkPs.setString(4, Instant.now().toString());
                chunkPs.executeUpdate();

                long chunkId = resolveChunkId(conn, sourceId, chunk.index());
                double[] embedding = embeddingClient.embed(chunk.content());
                embeddingPs.setLong(1, chunkId);
                embeddingPs.setString(2, embeddingModel);
                embeddingPs.setInt(3, embedding.length);
                embeddingPs.setString(4, serializeEmbedding(embedding));
                embeddingPs.setString(5, Instant.now().toString());
                embeddingPs.addBatch();
            }
            embeddingPs.executeBatch();
        }
    }

    private long resolveChunkId(Connection conn, long sourceId, int chunkIndex) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM rag_chunks WHERE source_id = ? AND chunk_index = ?")) {
            ps.setLong(1, sourceId);
            ps.setInt(2, chunkIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("failed to resolve rag chunk id for source=" + sourceId + ", chunk=" + chunkIndex);
    }

    // ── math / util ────────────────────────────────────────────

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    private String serializeEmbedding(double[] embedding) throws JsonProcessingException {
        List<Double> values = new ArrayList<>(embedding.length);
        for (double value : embedding) {
            values.add(value);
        }
        return OBJECT_MAPPER.writeValueAsString(values);
    }

    private double[] parseEmbedding(String json) throws IOException {
        List<Double> values = OBJECT_MAPPER.readValue(json, DOUBLE_LIST_TYPE);
        double[] embedding = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            embedding[i] = values.get(i);
        }
        return embedding;
    }

    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String sha256(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("failed to hash rag content", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ── records ────────────────────────────────────────────────

    private record TextChunk(int index, String content) {}

    private record ScoredChunk(String docId, String content, double score) {}
}
