package com.youkeda.project.wechatproject.bot.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQLite metadata index with payloads stored outside the database. */
public class IndexedArtifactStore implements ArtifactStore {

    private final Path databasePath;
    private final Path blobRoot;
    private final ArtifactValidator validator;

    public IndexedArtifactStore(Path databasePath, Path blobRoot, ArtifactValidator validator) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.blobRoot = blobRoot.toAbsolutePath().normalize();
        this.validator = validator != null ? validator : new ArtifactValidator();
        initialize();
    }

    @Override
    public ArtifactRef put(ArtifactWriteRequest request) throws IOException {
        ArtifactValidator.Validation validation = validator.validate(request);
        if (!validation.valid()) throw new IOException(validation.message());

        String id = UUID.randomUUID().toString();
        String extension = safeExtension(request.fileName());
        String storageKey = id.substring(0, 2) + "/" + id + extension;
        Path target = resolveStorageKey(storageKey);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), id, ".tmp");
        Files.write(temporary, request.bytes());
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            Instant now = Instant.now();
            ArtifactRef ref = new ArtifactRef(
                    id, request.recipientId(), request.requestId(), request.runId(), request.nodeId(),
                    Math.max(0, request.revision()), request.producerAgent(), request.type(), request.role(),
                    ArtifactStatus.VERIFIED, request.fileName(), request.mimeType(), request.bytes().length,
                    sha256(request.bytes()), request.description(), request.attributes(), now, request.expiresAt());
            insert(ref, storageKey);
            return ref;
        } catch (Exception e) {
            Files.deleteIfExists(target);
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to index artifact", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<ArtifactRef> find(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM artifact_index WHERE artifact_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readRef(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to query artifact", e);
        }
    }

    @Override
    public byte[] read(String artifactId) throws IOException {
        String sql = "SELECT storage_key,sha256 FROM artifact_index WHERE artifact_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IOException("artifact not found: " + artifactId);
                Path path = resolveStorageKey(rs.getString("storage_key"));
                byte[] bytes = Files.readAllBytes(path);
                if (!sha256(bytes).equalsIgnoreCase(rs.getString("sha256"))) {
                    throw new IOException("artifact checksum mismatch: " + artifactId);
                }
                return bytes;
            }
        } catch (SQLException e) {
            throw new IOException("failed to load artifact", e);
        }
    }

    @Override
    public ArtifactRef updateStatus(String artifactId, ArtifactStatus status) {
        String sql = "UPDATE artifact_index SET status=? WHERE artifact_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, artifactId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("artifact not found: " + artifactId);
            return find(artifactId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update artifact", e);
        }
    }

    @Override
    public List<ArtifactRef> findByRun(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        String sql = "SELECT * FROM artifact_index WHERE run_id=? ORDER BY created_at,artifact_id";
        List<ArtifactRef> result = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(readRef(rs));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list artifacts", e);
        }
    }

    @Override
    public void expireOlderRevisions(String runId, String nodeId, int currentRevision) {
        String sql = "UPDATE artifact_index SET status=? WHERE run_id=? AND node_id=? "
                + "AND revision<? AND status NOT IN (?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ArtifactStatus.EXPIRED.name());
            statement.setString(2, runId);
            statement.setString(3, nodeId);
            statement.setInt(4, currentRevision);
            statement.setString(5, ArtifactStatus.REJECTED.name());
            statement.setString(6, ArtifactStatus.EXPIRED.name());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to expire stale artifacts", e);
        }
    }

    private void initialize() {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createDirectories(blobRoot);
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS artifact_index (
                          artifact_id TEXT PRIMARY KEY,
                          recipient_id TEXT,
                          request_id TEXT,
                          run_id TEXT,
                          node_id TEXT,
                          revision INTEGER NOT NULL,
                          producer_agent TEXT,
                          artifact_type TEXT NOT NULL,
                          artifact_role TEXT NOT NULL,
                          status TEXT NOT NULL,
                          file_name TEXT NOT NULL,
                          mime_type TEXT,
                          size INTEGER NOT NULL,
                          sha256 TEXT NOT NULL,
                          storage_key TEXT NOT NULL,
                          description TEXT,
                          attributes TEXT,
                          created_at INTEGER NOT NULL,
                          expires_at INTEGER
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_artifact_run ON artifact_index(run_id,node_id,revision)");
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("failed to initialize artifact store", e);
        }
    }

    private void insert(ArtifactRef ref, String storageKey) throws SQLException {
        String sql = "INSERT INTO artifact_index(artifact_id,recipient_id,request_id,run_id,node_id,revision,"
                + "producer_agent,artifact_type,artifact_role,status,file_name,mime_type,size,sha256,storage_key,"
                + "description,attributes,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, ref.artifactId());
            statement.setString(index++, ref.recipientId());
            statement.setString(index++, ref.requestId());
            statement.setString(index++, ref.runId());
            statement.setString(index++, ref.nodeId());
            statement.setInt(index++, ref.revision());
            statement.setString(index++, ref.producerAgent());
            statement.setString(index++, ref.type().name());
            statement.setString(index++, ref.role().name());
            statement.setString(index++, ref.status().name());
            statement.setString(index++, ref.fileName());
            statement.setString(index++, ref.mimeType());
            statement.setLong(index++, ref.size());
            statement.setString(index++, ref.sha256());
            statement.setString(index++, storageKey);
            statement.setString(index++, ref.description());
            statement.setString(index++, encodeAttributes(ref.attributes()));
            statement.setLong(index++, ref.createdAt().toEpochMilli());
            if (ref.expiresAt() != null) statement.setLong(index, ref.expiresAt().toEpochMilli());
            else statement.setNull(index, java.sql.Types.BIGINT);
            statement.executeUpdate();
        }
    }

    private ArtifactRef readRef(ResultSet rs) throws SQLException {
        long expiresAt = rs.getLong("expires_at");
        boolean expiresAtNull = rs.wasNull();
        return new ArtifactRef(
                rs.getString("artifact_id"), rs.getString("recipient_id"), rs.getString("request_id"),
                rs.getString("run_id"), rs.getString("node_id"), rs.getInt("revision"),
                rs.getString("producer_agent"), ArtifactType.valueOf(rs.getString("artifact_type")),
                ArtifactRole.valueOf(rs.getString("artifact_role")), ArtifactStatus.valueOf(rs.getString("status")),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("size"),
                rs.getString("sha256"), rs.getString("description"), decodeAttributes(rs.getString("attributes")),
                Instant.ofEpochMilli(rs.getLong("created_at")), expiresAtNull ? null : Instant.ofEpochMilli(expiresAt));
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private Path resolveStorageKey(String storageKey) throws IOException {
        Path resolved = blobRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(blobRoot)) throw new IOException("invalid artifact storage key");
        return resolved;
    }

    private static String safeExtension(String fileName) {
        int dot = fileName != null ? fileName.lastIndexOf('.') : -1;
        if (dot < 0 || dot == fileName.length() - 1) return ".bin";
        String extension = fileName.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : ".bin";
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String encodeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return "";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return attributes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(encoder, entry.getKey()) + ":" + encode(encoder, entry.getValue()))
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static Map<String, String> decodeAttributes(String encoded) {
        if (encoded == null || encoded.isBlank()) return Map.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : encoded.split(",")) {
            String[] pair = item.split(":", 2);
            if (pair.length == 2) result.put(decode(decoder, pair[0]), decode(decoder, pair[1]));
        }
        return Map.copyOf(result);
    }

    private static String encode(Base64.Encoder encoder, String value) {
        return encoder.encodeToString((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(Base64.Decoder decoder, String value) {
        return new String(decoder.decode(value), StandardCharsets.UTF_8);
    }
}
