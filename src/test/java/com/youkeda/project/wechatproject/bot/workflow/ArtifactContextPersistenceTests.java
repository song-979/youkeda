package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactRef;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactRole;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactStatus;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactContextPersistenceTests {

    @TempDir
    Path tempDir;

    @Test
    void modelContextContainsArtifactIndexWithoutPayload() {
        DagTask task = taskWithArtifact();
        DagNode node = task.node("node-1");

        var context = DagContextMapper.forAgent(task, node, null);
        var record = context.records().getFirst();

        assertTrue(record.signals().get("artifact_index").contains("artifact-1|IMAGE|FINAL_OUTPUT"));
        assertEquals("[image generated]", record.result());
        assertFalse(record.toString().contains("data:image"));
        assertTrue(DagContextMapper.dependencyImageDataUrls(task, node).isEmpty());
    }

    @Test
    void artifactIndexSurvivesDagStoreRestart() {
        String database = tempDir.resolve("workflow.db").toString();
        new SqliteDagTaskStore(database).save(taskWithArtifact());

        DagTask restored = new SqliteDagTaskStore(database).findById("run-1").orElseThrow();
        ArtifactRef artifact = restored.node("node-1").result().artifacts().getFirst();

        assertEquals("artifact-1", artifact.artifactId());
        assertEquals(ArtifactStatus.VERIFIED, artifact.status());
        assertEquals(1, artifact.revision());
    }

    private static DagTask taskWithArtifact() {
        ArtifactRef artifact = new ArtifactRef(
                "artifact-1", "user-1", "request-1", "run-1", "node-1", 1,
                "IMAGE_GEN", ArtifactType.IMAGE, ArtifactRole.FINAL_OUTPUT,
                ArtifactStatus.VERIFIED, "generated.png", "image/png", 128,
                "abc123", "generated image", Map.of(), Instant.now(), null);
        AgentResult result = new AgentResult(
                "request-1", AgentResult.Status.SUCCESS, null, "[image generated]",
                null, AgentResult.ErrorKind.NONE, null, Map.of(), List.of(),
                Map.of("guard.result", "verified"), List.of(artifact));
        DagNode node = new DagNode("node-1", "image", "IMAGE_GEN", "draw", null,
                List.of(), Map.of(), 1);
        node.setResult(result);
        node.setStatus(DagNode.Status.SUCCEEDED);
        node.markResultRevision(1);
        DagTask task = new DagTask("run-1", "user-1", "draw an image");
        task.addNodes(List.of(node));
        return task;
    }
}
