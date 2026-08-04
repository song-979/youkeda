package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.model.ModelReply;

import java.util.List;

/** Immutable result returned by the DAG runtime to transport-facing code. */
public record DagRunOutcome(
        Status status,
        String finalReply,
        List<NodeOutput> nodeOutputs,
        String messageToUser,
        List<ModelReply.ImagePayload> pausedImages) {

    public enum Status { COMPLETED, WAITING_USER, PAUSED }

    public DagRunOutcome {
        nodeOutputs = nodeOutputs != null ? List.copyOf(nodeOutputs) : List.of();
        pausedImages = pausedImages != null ? List.copyOf(pausedImages) : List.of();
    }

    public static DagRunOutcome completed(String finalReply, List<NodeOutput> nodeOutputs) {
        return new DagRunOutcome(Status.COMPLETED, finalReply, nodeOutputs, null, List.of());
    }

    public static DagRunOutcome completed(String finalReply) {
        return completed(finalReply, List.of());
    }

    public static DagRunOutcome waiting(String message, List<ModelReply.ImagePayload> images) {
        return new DagRunOutcome(Status.WAITING_USER, null, List.of(), message, images);
    }

    public static DagRunOutcome paused(String message) {
        return new DagRunOutcome(Status.PAUSED, null, List.of(), message, List.of());
    }

    /** Stable snapshot of one DAG node result; mutable task nodes never escape the runtime. */
    public record NodeOutput(String nodeId, String agentType, AgentResult result) {
    }
}
