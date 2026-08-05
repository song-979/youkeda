package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.context.ContextTaskRecord;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Projects backend-owned DAG state into bounded model-facing context views. */
public final class DagContextMapper {

    private DagContextMapper() {
    }

    public static ContextTaskState forOrchestrator(DagTask workflow, String latestUserInput) {
        return new ContextTaskState(
                workflow.dagId(),
                workflow.status().name(),
                workflow.revision(),
                null,
                latestUserInput,
                "Original goal: " + safe(workflow.originalText()),
                workflow.nodes().stream().map(DagContextMapper::toRecord).toList());
    }

    public static ContextTaskState forAgent(DagTask workflow, DagNode currentNode,
                                            String latestUserInput) {
        Set<String> visibleIds = ancestorIds(workflow, currentNode);
        visibleIds.add(currentNode.id());
        List<ContextTaskRecord> records = workflow.nodes().stream()
                .filter(node -> visibleIds.contains(node.id()))
                .map(DagContextMapper::toRecord)
                .toList();
        String summary = currentNode.contextNote() != null && !currentNode.contextNote().isBlank()
                ? currentNode.contextNote() : null;
        return new ContextTaskState(
                workflow.dagId(),
                workflow.status().name(),
                workflow.revision(),
                currentNode.id(),
                latestUserInput,
                summary,
                records);
    }

    public static List<String> dependencyImageDataUrls(DagTask workflow, DagNode currentNode) {
        // Generated payloads stay in the artifact store. Model context receives metadata only.
        return List.of();
    }

    private static Set<String> ancestorIds(DagTask workflow, DagNode currentNode) {
        Set<String> ancestors = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(currentNode.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!ancestors.add(id)) {
                continue;
            }
            DagNode dependency = workflow.node(id);
            if (dependency != null) {
                queue.addAll(dependency.dependsOn());
            }
        }
        return ancestors;
    }

    private static ContextTaskRecord toRecord(DagNode node) {
        AgentResult result = node.result();
        java.util.Map<String, String> signals = new java.util.LinkedHashMap<>();
        if (result != null) {
            signals.putAll(result.signals());
            if (!result.artifacts().isEmpty()) {
                String index = result.artifacts().stream()
                        .map(artifact -> artifact.artifactId() + "|" + artifact.type()
                                + "|" + artifact.role() + "|" + artifact.status()
                                + "|" + safe(artifact.description()))
                        .reduce((a, b) -> a + ";" + b).orElse("");
                signals.put("artifact_index", index);
            }
        }
        return new ContextTaskRecord(
                node.id(),
                node.key(),
                node.agentType(),
                node.status().name(),
                node.dependsOn(),
                node.instruction(),
                result != null ? result.rawOutput() : null,
                result != null ? result.errorMessage() : null,
                result != null ? result.messageToUser() : null,
                java.util.Map.copyOf(signals));
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
