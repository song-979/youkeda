package com.youkeda.project.wechatproject.bot.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactRef;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactRole;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactStatus;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQLite snapshots for DAG tasks. Existing dag_workflow tables remain compatible on disk. */
public class SqliteDagTaskStore implements DagTaskStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteDagTaskStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final String ACTIVE_STATUSES =
            "'PLANNING','RUNNING','PAUSE_REQUESTED','PAUSED','WAITING_USER','SLEEPING'";

    private final String jdbcUrl;

    public SqliteDagTaskStore(String databasePath) {
        try {
            Path path = Path.of(databasePath == null || databasePath.isBlank()
                    ? "data/orchestration/workflows.db" : databasePath).toAbsolutePath().normalize();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + path;
            initialize();
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize DAG task store", e);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dag_workflow (
                      workflow_id TEXT PRIMARY KEY,
                      user_id TEXT NOT NULL,
                      original_text TEXT,
                      status TEXT NOT NULL,
                      revision INTEGER NOT NULL,
                      final_reply TEXT,
                      wait_message TEXT,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_dag_workflow_user ON dag_workflow(user_id, updated_at DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dag_node (
                      workflow_id TEXT NOT NULL,
                      node_id TEXT NOT NULL,
                      node_key TEXT NOT NULL,
                      agent_type TEXT NOT NULL,
                      instruction TEXT NOT NULL,
                      context_note TEXT,
                      dependencies_json TEXT NOT NULL,
                      parameters_json TEXT NOT NULL,
                      status TEXT NOT NULL,
                      attempt_count INTEGER NOT NULL,
                      max_attempts INTEGER NOT NULL,
                      next_attempt_at INTEGER NOT NULL,
                      result_status TEXT,
                      raw_output TEXT,
                      error_message TEXT,
                      error_kind TEXT,
                      message_to_user TEXT,
                      resume_state_json TEXT,
                      signals_json TEXT,
                      PRIMARY KEY (workflow_id, node_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dag_node_attempt (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      workflow_id TEXT NOT NULL,
                      node_id TEXT NOT NULL,
                      attempt_no INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      error_kind TEXT,
                      error_message TEXT,
                      raw_output TEXT,
                      started_at INTEGER NOT NULL,
                      finished_at INTEGER NOT NULL
                    )
                    """);
            addColumnIfMissing(statement, "dag_workflow", "input_revision", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "dag_workflow", "pending_inputs_json", "TEXT NOT NULL DEFAULT '[]'");
            addColumnIfMissing(statement, "dag_workflow", "focused", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "dag_node", "input_fingerprint", "TEXT");
            addColumnIfMissing(statement, "dag_node", "execution_revision", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "dag_node", "result_revision", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "dag_node", "artifacts_json", "TEXT NOT NULL DEFAULT '[]'");
        }
    }

    private static void addColumnIfMissing(Statement statement, String table, String column,
                                           String definition) throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) return;
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    @Override
    public synchronized void save(DagTask task) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                upsertTask(connection, task);
                for (DagNode node : task.nodes()) upsertNode(connection, task.dagId(), node);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist DAG task " + task.dagId(), e);
        }
    }

    @Override
    public synchronized List<DagTask> findActive() {
        String sql = "SELECT workflow_id FROM dag_workflow WHERE status IN (" + ACTIVE_STATUSES
                + ") ORDER BY updated_at DESC";
        List<DagTask> tasks = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) load(connection, rs.getString(1)).ifPresent(tasks::add);
        } catch (Exception e) {
            log.warn("failed to load active DAG tasks: {}", e.getMessage());
        }
        return List.copyOf(tasks);
    }

    @Override
    public synchronized Optional<DagTask> findFocused() {
        String sql = "SELECT workflow_id FROM dag_workflow WHERE status IN (" + ACTIVE_STATUSES
                + ") AND focused=1 ORDER BY updated_at DESC LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? load(connection, rs.getString(1)) : Optional.empty();
        } catch (Exception e) {
            log.warn("failed to load focused DAG task: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized Optional<DagTask> findById(String dagId) {
        try (Connection connection = open()) {
            return load(connection, dagId);
        } catch (Exception e) {
            log.warn("failed to load DAG dagId={}: {}", dagId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void setFocused(String dagId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clear = connection.prepareStatement("UPDATE dag_workflow SET focused=0 WHERE focused<>0");
                 PreparedStatement set = connection.prepareStatement(
                         "UPDATE dag_workflow SET focused=1, updated_at=? WHERE workflow_id=?")) {
                clear.executeUpdate();
                set.setLong(1, System.currentTimeMillis());
                set.setString(2, dagId);
                set.executeUpdate();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to focus DAG task " + dagId, e);
        }
    }

    @Override
    public synchronized void recordAttempt(String dagId, DagNode node, int attemptNo,
                                           long startedAt, long finishedAt, AgentResult result) {
        String sql = """
                INSERT INTO dag_node_attempt
                (workflow_id,node_id,attempt_no,status,error_kind,error_message,raw_output,started_at,finished_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dagId);
            statement.setString(2, node.id());
            statement.setInt(3, attemptNo);
            statement.setString(4, result != null ? result.status().name() : AgentResult.Status.FAILED.name());
            statement.setString(5, result != null ? result.errorKind().name() : AgentResult.ErrorKind.UNKNOWN.name());
            statement.setString(6, result != null ? result.errorMessage() : "missing result");
            statement.setString(7, result != null ? result.rawOutput() : null);
            statement.setLong(8, startedAt);
            statement.setLong(9, finishedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("failed to persist node attempt dagId={}, nodeId={}: {}", dagId, node.id(), e.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static void upsertTask(Connection connection, DagTask task) throws Exception {
        String sql = """
                INSERT INTO dag_workflow
                (workflow_id,user_id,original_text,status,revision,final_reply,wait_message,created_at,updated_at,
                 input_revision,pending_inputs_json,focused)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(workflow_id) DO UPDATE SET
                  status=excluded.status, revision=excluded.revision,
                  final_reply=excluded.final_reply, wait_message=excluded.wait_message,
                  updated_at=excluded.updated_at, input_revision=excluded.input_revision,
                  pending_inputs_json=excluded.pending_inputs_json, focused=excluded.focused
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.dagId());
            statement.setString(2, task.recipientId() != null ? task.recipientId() : "private-owner");
            statement.setString(3, task.originalText());
            statement.setString(4, task.status().name());
            statement.setInt(5, task.revision());
            statement.setString(6, task.finalReply());
            statement.setString(7, task.waitMessage());
            statement.setLong(8, task.createdAt());
            statement.setLong(9, task.updatedAt());
            statement.setInt(10, task.inputRevision());
            statement.setString(11, MAPPER.writeValueAsString(task.pendingUserInputs()));
            statement.setInt(12, task.focused() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertNode(Connection connection, String dagId, DagNode node) throws Exception {
        String sql = """
                INSERT INTO dag_node
                (workflow_id,node_id,node_key,agent_type,instruction,context_note,dependencies_json,parameters_json,
                 status,attempt_count,max_attempts,next_attempt_at,result_status,raw_output,error_message,error_kind,
                 message_to_user,resume_state_json,signals_json,input_fingerprint,execution_revision,result_revision,
                 artifacts_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(workflow_id,node_id) DO UPDATE SET
                  status=excluded.status, attempt_count=excluded.attempt_count,
                  next_attempt_at=excluded.next_attempt_at, result_status=excluded.result_status,
                  raw_output=excluded.raw_output, error_message=excluded.error_message,
                  error_kind=excluded.error_kind, message_to_user=excluded.message_to_user,
                  resume_state_json=excluded.resume_state_json, signals_json=excluded.signals_json,
                  input_fingerprint=excluded.input_fingerprint,
                  execution_revision=excluded.execution_revision, result_revision=excluded.result_revision,
                  artifacts_json=excluded.artifacts_json
                """;
        AgentResult result = node.result();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dagId);
            statement.setString(2, node.id());
            statement.setString(3, node.key());
            statement.setString(4, node.agentType());
            statement.setString(5, node.instruction());
            statement.setString(6, node.contextNote());
            statement.setString(7, MAPPER.writeValueAsString(node.dependsOn()));
            statement.setString(8, MAPPER.writeValueAsString(node.parameters()));
            statement.setString(9, node.status().name());
            statement.setInt(10, node.attemptCount());
            statement.setInt(11, node.maxAttempts());
            statement.setLong(12, node.nextAttemptAt());
            statement.setString(13, result != null ? result.status().name() : null);
            statement.setString(14, result != null ? result.rawOutput() : null);
            statement.setString(15, result != null ? result.errorMessage() : null);
            statement.setString(16, result != null ? result.errorKind().name() : null);
            statement.setString(17, result != null ? result.messageToUser() : null);
            statement.setString(18, MAPPER.writeValueAsString(result != null ? result.resumeState() : Map.of()));
            statement.setString(19, MAPPER.writeValueAsString(result != null ? result.signals() : Map.of()));
            statement.setString(20, node.inputFingerprint());
            statement.setInt(21, node.executionRevision());
            statement.setInt(22, node.resultRevision());
            statement.setString(23, artifactsJson(result != null ? result.artifacts() : List.of()));
            statement.executeUpdate();
        }
    }

    private Optional<DagTask> load(Connection connection, String dagId) throws Exception {
        DagTask task;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM dag_workflow WHERE workflow_id=?")) {
            statement.setString(1, dagId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                task = new DagTask(
                        rs.getString("workflow_id"), rs.getString("user_id"), rs.getString("original_text"),
                        DagTask.Status.valueOf(rs.getString("status")), rs.getInt("revision"),
                        rs.getInt("input_revision"), rs.getInt("focused") != 0,
                        rs.getString("final_reply"), rs.getString("wait_message"),
                        readStringList(rs.getString("pending_inputs_json")),
                        rs.getLong("created_at"), rs.getLong("updated_at"));
            }
        }

        List<DagNode> nodes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM dag_node WHERE workflow_id=? ORDER BY rowid")) {
            statement.setString(1, dagId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) nodes.add(readNode(rs));
            }
        }
        task.restoreNodes(nodes);
        return Optional.of(task);
    }

    private static DagNode readNode(ResultSet rs) throws Exception {
        String resultStatus = rs.getString("result_status");
        String errorMessage = rs.getString("error_message");
        AgentResult result = null;
        if (resultStatus != null) {
            AgentResult.Status status = AgentResult.Status.valueOf(resultStatus);
            String errorKind = rs.getString("error_kind");
            AgentResult.ErrorKind kind = errorKind != null
                    ? AgentResult.ErrorKind.valueOf(errorKind) : AgentResult.classify(errorMessage);
            String rawOutput = rs.getString("raw_output");
            result = new AgentResult(rs.getString("node_id"), status,
                    status == AgentResult.Status.SUCCESS ? rawOutput : null, rawOutput,
                    errorMessage, kind, rs.getString("message_to_user"),
                    readMap(rs.getString("resume_state_json")), List.of(),
                    readStringMap(rs.getString("signals_json")),
                    readArtifacts(rs.getString("artifacts_json")));
        }
        DagNode.Status nodeStatus = DagNode.Status.valueOf(rs.getString("status"));
        if (nodeStatus == DagNode.Status.RUNNING) nodeStatus = DagNode.Status.READY;
        return new DagNode(
                rs.getString("node_id"), rs.getString("node_key"), rs.getString("agent_type"),
                rs.getString("instruction"), rs.getString("context_note"),
                readStringList(rs.getString("dependencies_json")),
                readMap(rs.getString("parameters_json")), rs.getInt("max_attempts"),
                nodeStatus, rs.getInt("attempt_count"), rs.getLong("next_attempt_at"), result,
                rs.getString("input_fingerprint"), rs.getInt("execution_revision"),
                rs.getInt("result_revision"));
    }

    private static List<String> readStringList(String json) throws Exception {
        return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, STRING_LIST);
    }

    private static Map<String, Object> readMap(String json) throws Exception {
        return json == null || json.isBlank() ? Map.of() : MAPPER.readValue(json, OBJECT_MAP);
    }

    private static String artifactsJson(List<ArtifactRef> artifacts) throws Exception {
        List<Map<String, Object>> values = artifacts.stream().map(artifact -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("artifactId", artifact.artifactId());
            value.put("recipientId", artifact.recipientId());
            value.put("requestId", artifact.requestId());
            value.put("runId", artifact.runId());
            value.put("nodeId", artifact.nodeId());
            value.put("revision", artifact.revision());
            value.put("producerAgent", artifact.producerAgent());
            value.put("type", artifact.type().name());
            value.put("role", artifact.role().name());
            value.put("status", artifact.status().name());
            value.put("fileName", artifact.fileName());
            value.put("mimeType", artifact.mimeType());
            value.put("size", artifact.size());
            value.put("sha256", artifact.sha256());
            value.put("description", artifact.description());
            value.put("attributes", artifact.attributes());
            value.put("createdAt", artifact.createdAt().toEpochMilli());
            value.put("expiresAt", artifact.expiresAt() != null ? artifact.expiresAt().toEpochMilli() : null);
            return value;
        }).toList();
        return MAPPER.writeValueAsString(values);
    }

    private static List<ArtifactRef> readArtifacts(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        List<Map<String, Object>> values = MAPPER.readValue(json, new TypeReference<>() {});
        List<ArtifactRef> artifacts = new java.util.ArrayList<>();
        for (Map<String, Object> value : values) {
            Object expires = value.get("expiresAt");
            artifacts.add(new ArtifactRef(
                    string(value.get("artifactId")), string(value.get("recipientId")),
                    string(value.get("requestId")), string(value.get("runId")),
                    string(value.get("nodeId")), number(value.get("revision")).intValue(),
                    string(value.get("producerAgent")), ArtifactType.valueOf(string(value.get("type"))),
                    ArtifactRole.valueOf(string(value.get("role"))), ArtifactStatus.valueOf(string(value.get("status"))),
                    string(value.get("fileName")), string(value.get("mimeType")),
                    number(value.get("size")).longValue(), string(value.get("sha256")),
                    string(value.get("description")), stringMap(value.get("attributes")),
                    java.time.Instant.ofEpochMilli(number(value.get("createdAt")).longValue()),
                    expires != null ? java.time.Instant.ofEpochMilli(number(expires).longValue()) : null));
        }
        return List.copyOf(artifacts);
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : Long.parseLong(String.valueOf(value));
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    private static Map<String, String> readStringMap(String json) throws Exception {
        return json == null || json.isBlank() ? Map.of() : MAPPER.readValue(json, STRING_MAP);
    }
}
