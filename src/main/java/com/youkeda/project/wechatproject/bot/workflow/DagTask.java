package com.youkeda.project.wechatproject.bot.workflow;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One persistent DAG task: graph topology and execution state share the same aggregate. */
public class DagTask {

    public enum Status {
        PLANNING,
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        WAITING_USER,
        SUCCEEDED,
        PARTIAL_SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final String dagId;
    private final String recipientId;
    private final String originalText;
    private final LinkedHashMap<String, DagNode> nodes = new LinkedHashMap<>();
    private final List<String> pendingUserInputs = new ArrayList<>();
    private Status status;
    private int revision;
    private int inputRevision;
    private boolean focused;
    private String finalReply;
    private String waitMessage;
    private final long createdAt;
    private long updatedAt;

    public DagTask(String dagId, String recipientId, String originalText) {
        this(dagId, recipientId, originalText, Status.RUNNING, 1, 0, false,
                null, null, List.of(), Instant.now().toEpochMilli(), Instant.now().toEpochMilli());
    }

    public DagTask(String dagId, String recipientId, String originalText, Status status,
                   int revision, int inputRevision, boolean focused,
                   String finalReply, String waitMessage, List<String> pendingUserInputs,
                   long createdAt, long updatedAt) {
        this.dagId = dagId;
        this.recipientId = recipientId;
        this.originalText = originalText;
        this.status = status != null ? status : Status.RUNNING;
        this.revision = Math.max(1, revision);
        this.inputRevision = Math.max(0, inputRevision);
        this.focused = focused;
        this.finalReply = finalReply;
        this.waitMessage = waitMessage;
        if (pendingUserInputs != null) {
            pendingUserInputs.stream().filter(value -> value != null && !value.isBlank())
                    .forEach(this.pendingUserInputs::add);
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String dagId() { return dagId; }
    public String recipientId() { return recipientId; }
    public String originalText() { return originalText; }
    public Status status() { return status; }
    public int revision() { return revision; }
    public int inputRevision() { return inputRevision; }
    public boolean focused() { return focused; }
    public String finalReply() { return finalReply; }
    public String waitMessage() { return waitMessage; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public Collection<DagNode> nodes() { return List.copyOf(nodes.values()); }
    public DagNode node(String id) { return nodes.get(id); }
    public List<String> pendingUserInputs() { return List.copyOf(pendingUserInputs); }

    public void addNodes(Collection<DagNode> additions) {
        if (additions != null) {
            additions.forEach(node -> nodes.put(node.id(), node));
        }
        touch();
    }

    /** Restores persisted nodes without changing the snapshot timestamp used for focus ordering. */
    void restoreNodes(Collection<DagNode> restoredNodes) {
        if (restoredNodes != null) {
            restoredNodes.forEach(node -> nodes.put(node.id(), node));
        }
    }

    public void setStatus(Status status) { this.status = status; touch(); }
    public void setFocused(boolean focused) { this.focused = focused; touch(); }
    public void setFinalReply(String finalReply) { this.finalReply = finalReply; touch(); }
    public void setWaitMessage(String waitMessage) { this.waitMessage = waitMessage; touch(); }
    public void incrementRevision() { revision++; touch(); }

    public void addPendingUserInput(String input) {
        if (input != null && !input.isBlank()) {
            pendingUserInputs.add(input.trim());
            inputRevision++;
            touch();
        }
    }

    public String consumePendingUserInputs() {
        String combined = String.join("\n", pendingUserInputs);
        pendingUserInputs.clear();
        touch();
        return combined;
    }

    public String peekPendingUserInputs() {
        return String.join("\n", pendingUserInputs);
    }

    public void touch() { updatedAt = Instant.now().toEpochMilli(); }

    public boolean isActive() {
        return switch (status) {
            case PLANNING, RUNNING, PAUSE_REQUESTED, PAUSED, WAITING_USER -> true;
            default -> false;
        };
    }

    public List<DagNode> readyNodes(long now) {
        List<DagNode> ready = new ArrayList<>();
        for (DagNode node : nodes.values()) {
            if (node.status() == DagNode.Status.RETRY_WAIT && node.nextAttemptAt() <= now) {
                node.setStatus(DagNode.Status.PENDING);
            }
            if (node.status() != DagNode.Status.PENDING && node.status() != DagNode.Status.READY
                    && node.status() != DagNode.Status.INVALIDATED) {
                continue;
            }
            boolean dependenciesDone = node.dependsOn().stream()
                    .map(nodes::get)
                    .allMatch(dependency -> dependency != null && dependency.isSuccessfulTerminal());
            if (dependenciesDone) {
                node.setStatus(DagNode.Status.READY);
                ready.add(node);
            }
        }
        return ready;
    }

    public boolean allSuccessful() {
        return !nodes.isEmpty() && nodes.values().stream().allMatch(DagNode::isSuccessfulTerminal);
    }

    public boolean hasWaitingNode() {
        return nodes.values().stream().anyMatch(node -> node.status() == DagNode.Status.WAITING_USER);
    }

    public boolean hasFailedNode() {
        return nodes.values().stream().anyMatch(node -> node.status() == DagNode.Status.FAILED
                || node.status() == DagNode.Status.BLOCKED);
    }

    public boolean hasPendingWork() {
        return nodes.values().stream().anyMatch(node -> switch (node.status()) {
            case PENDING, READY, RUNNING, RETRY_WAIT, INVALIDATED -> true;
            default -> false;
        });
    }

    public DagNode findByKeyOrId(String value) {
        if (value == null || value.isBlank()) return null;
        DagNode direct = nodes.get(value);
        return direct != null ? direct
                : nodes.values().stream().filter(node -> value.equals(node.key())).findFirst().orElse(null);
    }

    public Set<String> descendantIds(String rootId) {
        Set<String> descendants = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (DagNode node : nodes.values()) {
                if (node.dependsOn().contains(current) && descendants.add(node.id())) {
                    queue.addLast(node.id());
                }
            }
        }
        return descendants;
    }

    public Map<String, DagNode> nodeMap() { return Map.copyOf(nodes); }
}
