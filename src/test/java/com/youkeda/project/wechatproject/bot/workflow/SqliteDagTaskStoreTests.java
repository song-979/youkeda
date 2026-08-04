package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDagTaskStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsDagTaskAndRecoversRunningNodeAsReady() throws Exception {
        Path database = tempDir.resolve("dag-task.db");
        SqliteDagTaskStore store = new SqliteDagTaskStore(database.toString());
        DagTask workflow = new DagTask("D-TEST0001", "private-owner", "do work");
        DagNode node = new DagNode("v1-work", "work", "CHAT", "work", null,
                List.of(), Map.of("value", 42), 3);
        node.setStatus(DagNode.Status.RUNNING);
        node.beginAttempt();
        node.beginAttempt(workflow.revision(), "fingerprint-1");
        workflow.addNodes(List.of(node));
        workflow.addPendingUserInput("补充信息");
        workflow.setStatus(DagTask.Status.PAUSE_REQUESTED);
        workflow.setFocused(true);
        store.save(workflow);
        AgentResult failed = AgentResult.failed(node.id(), "timeout", AgentResult.ErrorKind.TIMEOUT);
        store.recordAttempt(workflow.dagId(), node, 1, 10L, 20L, failed);

        DagTask restored = store.findById("D-TEST0001").orElseThrow();

        assertThat(restored.recipientId()).isEqualTo("private-owner");
        assertThat(restored.node("v1-work").status()).isEqualTo(DagNode.Status.READY);
        assertThat(restored.node("v1-work").attemptCount()).isEqualTo(2);
        assertThat(restored.node("v1-work").parameters()).containsEntry("value", 42);
        assertThat(restored.node("v1-work").inputFingerprint()).isEqualTo("fingerprint-1");
        assertThat(restored.pendingUserInputs()).containsExactly("补充信息");
        assertThat(restored.focused()).isTrue();
        assertThat(store.findFocused()).isPresent();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM dag_node_attempt")) {
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        restored.setStatus(DagTask.Status.SUCCEEDED);
        store.save(restored);
        assertThat(store.findActive()).isEmpty();
    }
}
