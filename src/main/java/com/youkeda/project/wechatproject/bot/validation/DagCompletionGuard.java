package com.youkeda.project.wechatproject.bot.validation;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.artifact.ArtifactStatus;
import com.youkeda.project.wechatproject.bot.workflow.DagNode;
import com.youkeda.project.wechatproject.bot.workflow.DagTask;

import java.util.ArrayList;
import java.util.List;

/** Checks backend facts before accepting the orchestrator's COMPLETE decision. */
public class DagCompletionGuard {

    public Validation validateComplete(DagTask task, boolean partial) {
        List<String> errors = new ArrayList<>();
        if (!partial && !task.allSuccessful()) {
            errors.add("COMPLETE requires every DAG node to be successfully terminal");
        }
        if (partial && task.nodes().stream().noneMatch(node -> node.status() == DagNode.Status.SUCCEEDED)) {
            errors.add("PARTIAL_COMPLETE requires at least one successful node");
        }
        if (task.nodes().stream().anyMatch(node -> node.status() == DagNode.Status.RUNNING)) {
            errors.add("completion cannot be accepted while a node is running");
        }
        for (DagNode node : task.nodes()) {
            if (node.status() != DagNode.Status.SUCCEEDED) continue;
            AgentResult result = node.result();
            if (result == null || result.status() != AgentResult.Status.SUCCESS) {
                errors.add("successful node has no successful AgentResult: " + node.id());
                continue;
            }
            if (!"verified".equals(result.signals().get("guard.result"))) {
                errors.add("node result was not guarded: " + node.id());
            }
            boolean invalidArtifact = result.artifacts().stream().anyMatch(ref ->
                    ref.status() != ArtifactStatus.VERIFIED || ref.revision() != node.resultRevision());
            if (invalidArtifact) errors.add("node has stale or unverified artifacts: " + node.id());
        }
        return new Validation(errors.isEmpty(), List.copyOf(errors));
    }

    public record Validation(boolean valid, List<String> errors) {}
}
