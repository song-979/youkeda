package com.youkeda.project.wechatproject.bot.service;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQLite is the rebuildable index layer. Markdown files remain the source of truth.
 */
public class VectorMemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryIndex.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.+$");
    private static final TypeReference<List<Double>> DOUBLE_LIST_TYPE = new TypeReference<>() {
    };

    private final Path dbPath;
    private final EmbeddingClient embeddingClient;
    private final int chunkChars;
    private final int overlapChars;
    private final int topK;
    private final double minScore;
    private final String embeddingModel;

    public VectorMemoryIndex(Path dbPath, EmbeddingClient embeddingClient,
                             int chunkChars, int overlapChars, int topK, double minScore) {
        this(dbPath, embeddingClient, chunkChars, overlapChars, topK, minScore, "unknown");
    }

    public VectorMemoryIndex(Path dbPath, EmbeddingClient embeddingClient,
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
            throw new IllegalStateException("failed to initialize memory vector index: " + this.dbPath, e);
        }
    }

    public String retrieve(String userId, String query, List<SourceDocument> documents) throws IOException {
        if (isBlank(userId) || documents == null || documents.isEmpty()) {
            return "";
        }

        try {
            synchronize(userId, documents);
            List<MemoryHit> hits = search(userId, query);
            if (hits.isEmpty()) {
                hits = lexicalSearch(query, documents);
            }
            logRetrieval(userId, query, hits);
            return formatHits(hits);
        } catch (IOException e) {
            log.warn("memory vector retrieval unavailable for userId={}; falling back to lexical search: {}",
                    userId, e.getMessage());
            List<MemoryHit> hits = lexicalSearch(query, documents);
            logRetrieval(userId, query, hits);
            return formatHits(hits);
        } catch (SQLException e) {
            throw new IOException("failed to retrieve memory chunks from SQLite index", e);
        }
    }

    private void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id TEXT NOT NULL,
                        source_path TEXT NOT NULL,
                        source_layer TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        source_mtime INTEGER NOT NULL,
                        chunk_count INTEGER NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE(user_id, source_path)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_id INTEGER NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        start_line INTEGER NOT NULL,
                        end_line INTEGER NOT NULL,
                        content_hash TEXT NOT NULL,
                        content TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE(source_id, chunk_index),
                        FOREIGN KEY(source_id) REFERENCES memory_index_sources(id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_embeddings (
                        chunk_id INTEGER PRIMARY KEY,
                        embedding_model TEXT NOT NULL,
                        dimensions INTEGER NOT NULL,
                        embedding_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(chunk_id) REFERENCES memory_index_chunks(id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_queries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id TEXT NOT NULL,
                        query_hash TEXT NOT NULL,
                        query_text TEXT NOT NULL,
                        top_k INTEGER NOT NULL,
                        min_score REAL NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_query_hits (
                        query_id INTEGER NOT NULL,
                        chunk_id INTEGER,
                        rank INTEGER NOT NULL,
                        score REAL NOT NULL,
                        source_path TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        FOREIGN KEY(query_id) REFERENCES memory_index_queries(id) ON DELETE CASCADE,
                        FOREIGN KEY(chunk_id) REFERENCES memory_index_chunks(id) ON DELETE SET NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_index_sources_user ON memory_index_sources(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_index_chunks_source ON memory_index_chunks(source_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_index_embeddings_model ON memory_index_embeddings(embedding_model)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_index_queries_user ON memory_index_queries(user_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_index_query_hits_query ON memory_index_query_hits(query_id)");
        }
    }

    private void synchronize(String userId, List<SourceDocument> documents) throws SQLException, IOException {
        if (nothingChanged(userId, documents)) {
            return;
        }
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                Map<ChunkKey, IndexedChunk> existingChunks = loadExistingChunks(conn, userId);
                Set<String> liveSources = new HashSet<>();

                for (SourceDocument document : documents) {
                    if (document == null || isBlank(document.content())) {
                        continue;
                    }
                    liveSources.add(document.sourcePath());
                    List<MarkdownChunk> chunks = splitMarkdown(document);
                    upsertChunks(conn, userId, document, chunks, existingChunks);
                    deleteRemovedChunks(conn, userId, document.sourcePath(), chunks.size());
                }

                deleteRemovedSources(conn, userId, liveSources);
                conn.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private boolean nothingChanged(String userId, List<SourceDocument> documents) throws IOException {
        List<SourceDocument> nonEmpty = new ArrayList<>();
        for (SourceDocument doc : documents) {
            if (doc != null && !isBlank(doc.content())) {
                nonEmpty.add(doc);
            }
        }
        if (nonEmpty.isEmpty()) {
            return true;
        }
        String sql = "SELECT source_path, content_hash, source_mtime FROM memory_index_sources WHERE user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            Map<String, StoredSource> stored = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stored.put(rs.getString("source_path"),
                            new StoredSource(rs.getString("content_hash"), rs.getLong("source_mtime")));
                }
            }
            if (stored.size() != nonEmpty.size()) {
                return false;
            }
            for (SourceDocument doc : nonEmpty) {
                StoredSource s = stored.get(doc.sourcePath());
                if (s == null) {
                    return false;
                }
                if (!sha256(doc.content()).equals(s.contentHash)) {
                    return false;
                }
            }
            return true;
        } catch (SQLException e) {
            log.debug("nothingChanged check failed, will re-sync: {}", e.getMessage());
            return false;
        }
    }

    private record StoredSource(String contentHash, long mtime) {
    }

    private Map<ChunkKey, IndexedChunk> loadExistingChunks(Connection conn, String userId) throws SQLException {
        Map<ChunkKey, IndexedChunk> chunks = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.source_path, c.chunk_index, c.content_hash, e.embedding_model
                FROM memory_index_chunks c
                JOIN memory_index_sources s ON s.id = c.source_id
                LEFT JOIN memory_index_embeddings e ON e.chunk_id = c.id
                WHERE s.user_id = ?
                """)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunks.put(new ChunkKey(rs.getString("source_path"), rs.getInt("chunk_index")),
                            new IndexedChunk(rs.getString("content_hash"), rs.getString("embedding_model")));
                }
            }
        }
        return chunks;
    }

    private void upsertChunks(Connection conn, String userId, SourceDocument document, List<MarkdownChunk> chunks,
                              Map<ChunkKey, IndexedChunk> existingChunks) throws SQLException, IOException {
        long sourceId = upsertSource(conn, userId, document, chunks.size());
        String chunkSql = """
                INSERT INTO memory_index_chunks (
                    source_id, chunk_index, start_line, end_line, content_hash, content, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_id, chunk_index) DO UPDATE SET
                    start_line = excluded.start_line,
                    end_line = excluded.end_line,
                    content_hash = excluded.content_hash,
                    content = excluded.content,
                    updated_at = excluded.updated_at
                """;
        String embeddingSql = """
                INSERT INTO memory_index_embeddings (
                    chunk_id, embedding_model, dimensions, embedding_json, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(chunk_id) DO UPDATE SET
                    embedding_model = excluded.embedding_model,
                    dimensions = excluded.dimensions,
                    embedding_json = excluded.embedding_json,
                    updated_at = excluded.updated_at
                """;

        try (PreparedStatement chunkPs = conn.prepareStatement(chunkSql);
             PreparedStatement embeddingPs = conn.prepareStatement(embeddingSql)) {
            for (MarkdownChunk chunk : chunks) {
                String hash = sha256(chunk.content());
                ChunkKey key = new ChunkKey(document.sourcePath(), chunk.index());
                IndexedChunk existing = existingChunks.get(key);
                if (existing != null
                        && hash.equals(existing.contentHash())
                        && embeddingModel.equals(existing.embeddingModel())) {
                    continue;
                }

                double[] embedding = embeddingClient.embed(chunk.content());
                chunkPs.setLong(1, sourceId);
                chunkPs.setInt(2, chunk.index());
                chunkPs.setInt(3, chunk.startLine());
                chunkPs.setInt(4, chunk.endLine());
                chunkPs.setString(5, hash);
                chunkPs.setString(6, chunk.content());
                chunkPs.setString(7, Instant.now().toString());
                chunkPs.executeUpdate();

                long chunkId = chunkId(conn, sourceId, chunk.index());
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

    private long upsertSource(Connection conn, String userId, SourceDocument document, int chunkCount)
            throws SQLException, IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO memory_index_sources (
                    user_id, source_path, source_layer, content_hash, source_mtime, chunk_count, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, source_path) DO UPDATE SET
                    source_layer = excluded.source_layer,
                    content_hash = excluded.content_hash,
                    source_mtime = excluded.source_mtime,
                    chunk_count = excluded.chunk_count,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, userId);
            ps.setString(2, document.sourcePath());
            ps.setString(3, document.layer());
            ps.setString(4, sha256(document.content()));
            ps.setLong(5, document.mtimeMillis());
            ps.setInt(6, chunkCount);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id FROM memory_index_sources
                WHERE user_id = ? AND source_path = ?
                """)) {
            ps.setString(1, userId);
            ps.setString(2, document.sourcePath());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("failed to resolve memory source id for " + document.sourcePath());
    }

    private long chunkId(Connection conn, long sourceId, int chunkIndex) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id FROM memory_index_chunks
                WHERE source_id = ? AND chunk_index = ?
                """)) {
            ps.setLong(1, sourceId);
            ps.setInt(2, chunkIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("failed to resolve memory chunk id for source=" + sourceId + ", chunk=" + chunkIndex);
    }

    private void deleteRemovedChunks(Connection conn, String userId, String sourcePath, int chunkCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM memory_index_chunks
                WHERE source_id = (
                    SELECT id FROM memory_index_sources
                    WHERE user_id = ? AND source_path = ?
                )
                AND chunk_index >= ?
                """)) {
            ps.setString(1, userId);
            ps.setString(2, sourcePath);
            ps.setInt(3, chunkCount);
            ps.executeUpdate();
        }
    }

    private void deleteRemovedSources(Connection conn, String userId, Set<String> liveSources) throws SQLException {
        if (liveSources.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_index_sources WHERE user_id = ?")) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
            return;
        }

        String placeholders = String.join(",", liveSources.stream().map(ignored -> "?").toList());
        String sql = "DELETE FROM memory_index_sources WHERE user_id = ? AND source_path NOT IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            int index = 2;
            for (String source : liveSources) {
                ps.setString(index++, source);
            }
            ps.executeUpdate();
        }
    }

    private List<MemoryHit> search(String userId, String query) throws SQLException, IOException {
        if (isBlank(query)) {
            return List.of();
        }

        double[] queryEmbedding = embeddingClient.embed(query);
        List<MemoryHit> hits = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT c.id AS chunk_id, s.source_path, s.source_layer, c.chunk_index,
                            c.start_line, c.end_line, c.content, e.embedding_json
                     FROM memory_index_chunks c
                     JOIN memory_index_sources s ON s.id = c.source_id
                     JOIN memory_index_embeddings e ON e.chunk_id = c.id
                     WHERE s.user_id = ?
                     """)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] embedding = parseEmbedding(rs.getString("embedding_json"));
                    double score = cosine(queryEmbedding, embedding);
                    if (score >= minScore) {
                        hits.add(new MemoryHit(
                                rs.getLong("chunk_id"),
                                rs.getString("source_path"),
                                rs.getString("source_layer"),
                                rs.getInt("chunk_index"),
                                rs.getInt("start_line"),
                                rs.getInt("end_line"),
                                score,
                                rs.getString("content")));
                    }
                }
            }
        }

        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed()
                .thenComparing(MemoryHit::sourcePath)
                .thenComparingInt(MemoryHit::chunkIndex));
        if (hits.size() > topK) {
            return new ArrayList<>(hits.subList(0, topK));
        }
        return hits;
    }

    private List<MemoryHit> lexicalSearch(String query, List<SourceDocument> documents) {
        if (isBlank(query)) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<MemoryHit> hits = new ArrayList<>();
        for (SourceDocument document : documents) {
            for (MarkdownChunk chunk : splitMarkdown(document)) {
                String lower = chunk.content().toLowerCase(Locale.ROOT);
                int matched = 0;
                for (String term : terms) {
                    if (lower.contains(term)) {
                        matched++;
                    }
                }
                if (matched > 0) {
                    double score = (double) matched / terms.size();
                    hits.add(new MemoryHit(-1L, document.sourcePath(), document.layer(), chunk.index(),
                            chunk.startLine(), chunk.endLine(), score, chunk.content()));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed()
                .thenComparing(MemoryHit::sourcePath)
                .thenComparingInt(MemoryHit::chunkIndex));
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    private String formatHits(List<MemoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("OpenClaw memory retrieval. Memory source is workspace Markdown; SQLite is only a rebuildable index. ");
        sb.append("Use these relevant fragments as contextual hints, not as hidden facts.\n\n");
        sb.append("Relevant memory chunks:\n");
        for (MemoryHit hit : hits) {
            sb.append("- [")
                    .append(hit.layer())
                    .append("] ")
                    .append(hit.sourcePath())
                    .append(":")
                    .append(hit.startLine())
                    .append("-")
                    .append(hit.endLine())
                    .append(" score=")
                    .append(String.format(Locale.ROOT, "%.3f", hit.score()))
                    .append("\n")
                    .append(indent(hit.content()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private void logRetrieval(String userId, String query, List<MemoryHit> hits) {
        if (isBlank(userId) || isBlank(query)) {
            return;
        }
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                long queryId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memory_index_queries (
                            user_id, query_hash, query_text, top_k, min_score, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setString(1, userId);
                    ps.setString(2, sha256(query));
                    ps.setString(3, query);
                    ps.setInt(4, topK);
                    ps.setDouble(5, minScore);
                    ps.setString(6, Instant.now().toString());
                    ps.executeUpdate();
                }
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                    if (!rs.next()) {
                        conn.rollback();
                        return;
                    }
                    queryId = rs.getLong(1);
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memory_index_query_hits (
                            query_id, chunk_id, rank, score, source_path, chunk_index
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    int rank = 1;
                    for (MemoryHit hit : hits) {
                        ps.setLong(1, queryId);
                        if (hit.chunkId() > 0) {
                            ps.setLong(2, hit.chunkId());
                        } else {
                            ps.setNull(2, java.sql.Types.INTEGER);
                        }
                        ps.setInt(3, rank++);
                        ps.setDouble(4, hit.score());
                        ps.setString(5, hit.sourcePath());
                        ps.setInt(6, hit.chunkIndex());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException | IOException e) {
            log.debug("failed to write memory retrieval audit log", e);
        }
    }

    private List<MarkdownChunk> splitMarkdown(SourceDocument document) {
        List<MarkdownChunk> chunks = new ArrayList<>();
        String[] lines = document.content().split("\\R", -1);
        String currentHeading = "";
        StringBuilder current = new StringBuilder();
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (HEADING.matcher(line).matches()) {
                currentHeading = line.trim();
                if (!current.isEmpty()) {
                    chunks.add(newChunk(chunks.size(), current.toString(), startLine, i));
                    current.setLength(0);
                }
                startLine = i + 1;
            }

            String candidate = line + "\n";
            if (!current.isEmpty() && current.length() + candidate.length() > chunkChars) {
                chunks.add(newChunk(chunks.size(), current.toString(), startLine, i));
                String tail = overlapTail(current.toString());
                current.setLength(0);
                if (!isBlank(currentHeading)) {
                    current.append(currentHeading).append("\n");
                }
                if (!tail.isBlank()) {
                    current.append(tail).append("\n");
                }
                startLine = Math.max(1, i);
            }
            current.append(candidate);
        }

        if (!current.isEmpty()) {
            chunks.add(newChunk(chunks.size(), current.toString(), startLine, lines.length));
        }
        return chunks.stream()
                .filter(chunk -> !isBlank(stripMarkdownNoise(chunk.content())))
                .toList();
    }

    private MarkdownChunk newChunk(int index, String content, int startLine, int endLine) {
        return new MarkdownChunk(index, Math.max(1, startLine), Math.max(startLine, endLine), content.trim());
    }

    private String overlapTail(String text) {
        if (overlapChars <= 0 || text == null || text.length() <= overlapChars) {
            return "";
        }
        return text.substring(text.length() - overlapChars).trim();
    }

    private String stripMarkdownNoise(String content) {
        return content == null ? "" : content
                .replaceAll("(?m)^#{1,6}\\s+.*$", "")
                .replaceAll("[-*`#>\\s]", "")
                .trim();
    }

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
            throw new IOException("failed to hash memory chunk", e);
        }
    }

    private List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        Map<String, Boolean> terms = new LinkedHashMap<>();
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 2) {
                terms.put(term, Boolean.TRUE);
            }
        }
        return new ArrayList<>(terms.keySet());
    }

    private static String indent(String text) {
        return "  " + (text == null ? "" : text.replace("\n", "\n  "));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SourceDocument(String sourcePath, String layer, long mtimeMillis, String content) {
    }

    private record MarkdownChunk(int index, int startLine, int endLine, String content) {
    }

    private record ChunkKey(String sourcePath, int chunkIndex) {
    }

    private record IndexedChunk(String contentHash, String embeddingModel) {
    }

    private record MemoryHit(long chunkId, String sourcePath, String layer, int chunkIndex,
                             int startLine, int endLine, double score, String content) {
    }
}
