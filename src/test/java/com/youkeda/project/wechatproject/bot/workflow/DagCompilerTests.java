package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DagCompilerTests {

    private final AgentRegistry registry = new AgentRegistry(List.of(agent("CHAT"), agent("BROWSER")), null);
    private final DagCompiler compiler = new DagCompiler(registry, 8, 3);

    @Test
    void compilesParallelRootsAndBackendOwnedDependencies() {
        DagTask workflow = new DagTask("wf-1", "user-1", "request");

        DagCompiler.CompilationResult result = compiler.compile(workflow, List.of(
                node("research", "CHAT", List.of()),
                node("browse", "BROWSER", List.of()),
                node("merge", "CHAT", List.of("research", "browse"))), 1);

        assertThat(result.valid()).isTrue();
        assertThat(result.nodes()).extracting(DagNode::id)
                .containsExactly("v1-research", "v1-browse", "v1-merge");
        assertThat(result.nodes().get(2).dependsOn())
                .containsExactly("v1-research", "v1-browse");
    }

    @Test
    void rejectsUnknownAgentDependencyDuplicateAndCycle() {
        assertThat(compiler.compile(new DagTask("wf-a", "u", "r"),
                List.of(node("x", "MISSING", List.of())), 1).errors())
                .anyMatch(error -> error.contains("unknown agent"));

        assertThat(compiler.compile(new DagTask("wf-b", "u", "r"),
                List.of(node("x", "CHAT", List.of("missing"))), 1).errors())
                .anyMatch(error -> error.contains("unknown dependency"));

        assertThat(compiler.compile(new DagTask("wf-c", "u", "r"),
                List.of(node("x", "CHAT", List.of()), node("x", "CHAT", List.of())), 1).errors())
                .anyMatch(error -> error.contains("duplicate node key"));

        assertThat(compiler.compile(new DagTask("wf-d", "u", "r"),
                List.of(node("a", "CHAT", List.of("b")), node("b", "CHAT", List.of("a"))), 1).errors())
                .contains("dependency graph contains a cycle");
    }

    private static DagNodeDraft node(String key, String agent, List<String> dependencies) {
        return new DagNodeDraft(key, agent, "execute " + key, null, dependencies, Map.of());
    }

    private static AgentUnit agent(String name) {
        return new AgentUnit() {
            @Override public String getName() { return name; }
            @Override public AgentCapability getCapability() {
                return new AgentCapability(name, name, List.of(), "text");
            }
            @Override public AgentResult execute(AgentTask task) {
                return AgentResult.success(task.taskId(), "ok", "ok");
            }
        };
    }
}
