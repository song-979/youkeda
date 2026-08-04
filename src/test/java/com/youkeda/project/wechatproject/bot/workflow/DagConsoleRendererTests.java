package com.youkeda.project.wechatproject.bot.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DagConsoleRendererTests {

    @Test
    void rendersUncompiledTaskStateInsteadOfHidingGraphOutput() {
        DagTask task = new DagTask("D-PENDING1", "owner", "request");
        task.setStatus(DagTask.Status.PLANNING);

        assertThat(DagConsoleRenderer.render(task)).contains(
                "DAG dagId=D-PENDING1 revision=1 status=PLANNING nodes=0",
                "TOPOLOGY (topological stages)",
                "| graph not compiled",
                "DIRECTED EDGES (dependency ----> dependent)\n(none)");
    }

    @Test
    void rendersParallelStagesAndDirectedEdges() {
        DagTask workflow = new DagTask("wf-log", "user", "request");
        DagNode left = node("v1-left", "left", List.of());
        DagNode right = node("v1-right", "right", List.of());
        DagNode merge = node("v1-merge", "merge", List.of(left.id(), right.id()));
        workflow.addNodes(List.of(left, right, merge));

        String graph = DagConsoleRenderer.render(workflow);

        assertThat(graph).contains(
                "DAG dagId=wf-log revision=1 status=RUNNING nodes=3",
                "stage 1 [PARALLEL x2]",
                "stage 2 [SERIAL x1]",
                "| v1-left [left]",
                "| v1-right [right]",
                "| v1-merge [merge]",
                "| v1-left                  | ----> | v1-merge",
                "| v1-right                 | ----> | v1-merge")
                .doesNotContain("private instruction", "secret", "not-rendered");
    }

    private static DagNode node(String id, String key, List<String> dependencies) {
        return new DagNode(id, key, "CHAT", "private instruction", null,
                dependencies, Map.of("secret", "not-rendered"), 3);
    }
}
