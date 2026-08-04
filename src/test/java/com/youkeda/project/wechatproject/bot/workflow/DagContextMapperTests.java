package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DagContextMapperTests {

    @Test
    void agentViewContainsOnlyCurrentNodeAndTransitiveDependencies() {
        DagTask workflow = new DagTask("wf", "user", "original private conversation");
        DagNode rootA = node("a", "root-a", List.of());
        DagNode rootB = node("b", "unrelated-secret", List.of());
        DagNode child = node("child", "publish", List.of("a"));
        workflow.addNodes(List.of(rootA, rootB, child));
        succeed(rootA, "allowed-result");
        succeed(rootB, "secret-parallel-result");

        ContextTaskState agentView = DagContextMapper.forAgent(workflow, child, null);
        ContextTaskState orchestratorView = DagContextMapper.forOrchestrator(workflow, null);

        assertThat(agentView.records()).extracting(record -> record.id())
                .containsExactly("a", "child");
        assertThat(agentView.records()).extracting(record -> record.result())
                .contains("allowed-result").doesNotContain("secret-parallel-result");
        assertThat(agentView.summary()).isNull();
        assertThat(orchestratorView.records()).extracting(record -> record.id())
                .containsExactly("a", "b", "child");
    }

    private static DagNode node(String id, String instruction, List<String> dependencies) {
        return new DagNode(id, id, "CHAT", instruction, null, dependencies, Map.of(), 3);
    }

    private static void succeed(DagNode node, String output) {
        node.setResult(AgentResult.success(node.id(), output, output));
        node.setStatus(DagNode.Status.SUCCEEDED);
    }
}
