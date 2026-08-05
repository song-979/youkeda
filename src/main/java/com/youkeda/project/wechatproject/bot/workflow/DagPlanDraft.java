package com.youkeda.project.wechatproject.bot.workflow;

import java.util.List;

public record DagPlanDraft(
        Status status,
        String reasoning,
        List<DagNodeDraft> nodes,
        String finalReply,
        String question) {

    public enum Status { DAG, COMPLETED, ASK_USER, INVALID, UNAVAILABLE }

    public DagPlanDraft {
        status = status != null ? status : Status.INVALID;
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
    }

    public static DagPlanDraft dag(String reasoning, List<DagNodeDraft> nodes) {
        return new DagPlanDraft(Status.DAG, reasoning, nodes, null, null);
    }

    public static DagPlanDraft completed(String reasoning, String finalReply) {
        return new DagPlanDraft(Status.COMPLETED, reasoning, List.of(), finalReply, null);
    }

    public static DagPlanDraft askUser(String reasoning, String question) {
        return new DagPlanDraft(Status.ASK_USER, reasoning, List.of(), null, question);
    }

    public static DagPlanDraft invalid(String reasoning) {
        return new DagPlanDraft(Status.INVALID, reasoning, List.of(), null, null);
    }

    public static DagPlanDraft unavailable(String reasoning) {
        return new DagPlanDraft(Status.UNAVAILABLE, reasoning, List.of(), null, null);
    }
}
