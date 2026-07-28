package com.youkeda.project.wechatproject.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SQLiteConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(SQLiteConversationMemory.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final String jdbcUrl;
    private final int maxMessages;
    private final long ttlMillis;

    public SQLiteConversationMemory(String dbPath, int maxHistoryRounds, int memoryTtlMinutes) {
        Path databasePath = resolveDatabasePath(dbPath);
        createParentDirectories(databasePath);
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
        this.maxMessages = maxHistoryRounds * 2;
        this.ttlMillis = memoryTtlMinutes * 60_000L;
        initializeSchema();
    }

    @Override
    public List<ChatRequest.Message> getHistory(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        try (Connection connection = openConnection()) {
            cleanupExpired(connection);
            if (!sessionExists(connection, userId)) {
                return List.of();
            }
            updateLastAccess(connection, userId, System.currentTimeMillis());
            return loadMessages(connection, userId);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load conversation history for user=" + userId, e);
        }
    }

    @Override
    public void append(String userId, String userMessage, String assistantReply) {
        if (isBlank(userId)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = openConnection()) {
            cleanupExpired(connection);
            upsertSession(connection, userId, now);
            insertMessage(connection, userId, "user", userMessage, now);
            insertMessage(connection, userId, "assistant", assistantReply, now);
            trimMessages(connection, userId);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to append conversation history for user=" + userId, e);
        }
    }

    @Override
    public void appendUserMessage(String userId, String userMessage) {
        if (isBlank(userId)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = openConnection()) {
            cleanupExpired(connection);
            upsertSession(connection, userId, now);
            insertMessage(connection, userId, "user", userMessage, now);
            trimMessages(connection, userId);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to append user message for user=" + userId, e);
        }
    }

    @Override
    public void clear(String userId) {
        if (isBlank(userId)) {
            return;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation_sessions WHERE user_id = ?")) {
            statement.setString(1, userId);
            statement.executeUpdate();
            log.debug("history cleared for user={}", userId);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to clear conversation history for user=" + userId, e);
        }
    }

    @Override
    public void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
        if (isBlank(userId) || imageBase64Urls == null || imageBase64Urls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO conversation_sessions (user_id, last_access, latest_image_urls, latest_image_summary)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT(user_id) DO UPDATE SET
                         last_access = excluded.last_access,
                         latest_image_urls = excluded.latest_image_urls,
                         latest_image_summary = excluded.latest_image_summary
                     """)) {
            cleanupExpired(connection);
            statement.setString(1, userId);
            statement.setLong(2, now);
            statement.setString(3, writeJson(imageBase64Urls));
            statement.setString(4, summary);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save image context for user=" + userId, e);
        }
    }

    @Override
    public List<String> getLatestImageDataUrls(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        try (Connection connection = openConnection()) {
            cleanupExpired(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT latest_image_urls FROM conversation_sessions WHERE user_id = ?")) {
                statement.setString(1, userId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return List.of();
                    }
                    updateLastAccess(connection, userId, System.currentTimeMillis());
                    return readJsonList(rs.getString("latest_image_urls"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load image context for user=" + userId, e);
        }
    }

    @Override
    public String getLatestImageSummary(String userId) {
        if (isBlank(userId)) {
            return null;
        }
        try (Connection connection = openConnection()) {
            cleanupExpired(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT latest_image_summary FROM conversation_sessions WHERE user_id = ?")) {
                statement.setString(1, userId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    updateLastAccess(connection, userId, System.currentTimeMillis());
                    return rs.getString("latest_image_summary");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load image summary for user=" + userId, e);
        }
    }

    List<StoredMessage> getStoredMessages(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, role, content, created_at
                     FROM conversation_messages
                     WHERE user_id = ?
                     ORDER BY id ASC
                     """)) {
            statement.setString(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<StoredMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(new StoredMessage(
                            rs.getLong("id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getLong("created_at")));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load stored messages for user=" + userId, e);
        }
    }

    boolean updateMessage(long messageId, String newContent) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE conversation_messages SET content = ? WHERE id = ?")) {
            statement.setString(1, newContent);
            statement.setLong(2, messageId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update message id=" + messageId, e);
        }
    }

    boolean deleteMessage(long messageId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation_messages WHERE id = ?")) {
            statement.setLong(1, messageId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete message id=" + messageId, e);
        }
    }

    private void initializeSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_sessions (
                        user_id TEXT PRIMARY KEY,
                        last_access INTEGER NOT NULL,
                        latest_image_urls TEXT,
                        latest_image_summary TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES conversation_sessions(user_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_id
                    ON conversation_messages(user_id, id)
                    """);
            log.info("sqlite conversation memory ready: {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize sqlite conversation schema", e);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private void cleanupExpired(Connection connection) throws SQLException {
        long expireBefore = System.currentTimeMillis() - ttlMillis;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM conversation_sessions WHERE last_access < ?")) {
            statement.setLong(1, expireBefore);
            statement.executeUpdate();
        }
    }

    private boolean sessionExists(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM conversation_sessions WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void upsertSession(Connection connection, String userId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conversation_sessions (user_id, last_access, latest_image_urls, latest_image_summary)
                VALUES (?, ?, NULL, NULL)
                ON CONFLICT(user_id) DO UPDATE SET last_access = excluded.last_access
                """)) {
            statement.setString(1, userId);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private void updateLastAccess(Connection connection, String userId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation_sessions SET last_access = ? WHERE user_id = ?")) {
            statement.setLong(1, now);
            statement.setString(2, userId);
            statement.executeUpdate();
        }
    }

    private void insertMessage(Connection connection, String userId, String role, String content, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conversation_messages (user_id, role, content, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, userId);
            statement.setString(2, role);
            statement.setString(3, content != null ? content : "");
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private void trimMessages(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM conversation_messages
                WHERE user_id = ?
                  AND id NOT IN (
                      SELECT id FROM conversation_messages
                      WHERE user_id = ?
                      ORDER BY id DESC
                      LIMIT ?
                  )
                """)) {
            statement.setString(1, userId);
            statement.setString(2, userId);
            statement.setInt(3, maxMessages);
            statement.executeUpdate();
        }
    }

    private List<ChatRequest.Message> loadMessages(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, content
                FROM conversation_messages
                WHERE id IN (
                    SELECT id FROM conversation_messages
                    WHERE user_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                )
                ORDER BY id ASC
                """)) {
            statement.setString(1, userId);
            statement.setInt(2, maxMessages);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatRequest.Message> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(new ChatRequest.Message(rs.getString("role"), rs.getString("content")));
                }
                return messages;
            }
        }
    }

    private static Path resolveDatabasePath(String dbPath) {
        if (isBlank(dbPath)) {
            throw new IllegalArgumentException("SQLite database path must not be blank");
        }
        return Paths.get(dbPath).toAbsolutePath().normalize();
    }

    private static void createParentDirectories(Path databasePath) {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to create sqlite parent directory: " + databasePath, e);
        }
    }

    private static List<String> readJsonList(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, STRING_LIST);
        } catch (IOException e) {
            log.warn("failed to parse image url list, returning empty list", e);
            return List.of();
        }
    }

    private static String writeJson(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize image url list", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record StoredMessage(long id, String role, String content, long createdAt) {
    }
}
