package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles planner keys into backend-owned node IDs and validates the resulting graph. */
public class DagCompiler {

    private final AgentRegistry registry;
    private final int maxNodes;
    private final int defaultMaxAttempts;

    public DagCompiler(AgentRegistry registry, int maxNodes, int defaultMaxAttempts) {
        this.registry = registry;
        this.maxNodes = Math.max(1, maxNodes);
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
    }

    public CompilationResult compile(DagTask workflow, List<DagNodeDraft> drafts, int targetRevision) {
        List<String> errors = new ArrayList<>();
        if (drafts == null || drafts.isEmpty()) {
            return CompilationResult.failure(List.of("plan contains no nodes"));
        }
        if (workflow.nodes().size() + drafts.size() > maxNodes) {
            errors.add("node limit exceeded: max=" + maxNodes);
        }

        Map<String, String> existingRefs = new HashMap<>();
        for (DagNode node : workflow.nodes()) {
            existingRefs.put(node.id(), node.id());
            existingRefs.put(node.key(), node.id());
        }

        Map<String, String> newIds = new LinkedHashMap<>();
        int index = 0;
        for (DagNodeDraft draft : drafts) {
            index++;
            String key = draft.key() != null ? draft.key().trim() : "";
            if (key.isBlank()) {
                key = "node-" + index;
            }
            if (existingRefs.containsKey(key) || newIds.containsKey(key)) {
                errors.add("duplicate node key: " + key);
                continue;
            }
            String id = uniqueId(workflow, newIds.values(), targetRevision, key, index);
            newIds.put(key, id);
        }

        List<DagNode> additions = new ArrayList<>();
        index = 0;
        for (DagNodeDraft draft : drafts) {
            index++;
            String key = draft.key() != null && !draft.key().isBlank() ? draft.key().trim() : "node-" + index;
            String id = newIds.get(key);
            if (id == null) {
                continue;
            }
            String agentType = draft.agentType() != null
                    ? draft.agentType().trim().toUpperCase(Locale.ROOT) : "";
            if (!registry.contains(agentType)) {
                errors.add("unknown agent for " + key + ": " + agentType);
            }
            if (draft.instruction() == null || draft.instruction().isBlank()) {
                errors.add("missing instruction for " + key);
            }

            List<String> dependencies = new ArrayList<>();
            for (String reference : draft.dependsOn()) {
                String dependencyId = newIds.get(reference);
                if (dependencyId == null) {
                    dependencyId = existingRefs.get(reference);
                }
                if (dependencyId == null) {
                    errors.add("unknown dependency for " + key + ": " + reference);
                } else if (dependencyId.equals(id)) {
                    errors.add("self dependency for " + key);
                } else if (!dependencies.contains(dependencyId)) {
                    dependencies.add(dependencyId);
                }
            }

            additions.add(new DagNode(id, key, agentType, draft.instruction(), draft.contextNote(),
                    dependencies, draft.parameters(), defaultMaxAttempts));
        }

        if (errors.isEmpty()) {
            errors.addAll(validateAcyclic(workflow, additions));
        }
        return errors.isEmpty() ? CompilationResult.success(additions) : CompilationResult.failure(errors);
    }

    private List<String> validateAcyclic(DagTask workflow, List<DagNode> additions) {
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        List<DagNode> all = new ArrayList<>(workflow.nodes());
        all.addAll(additions);
        for (DagNode node : all) {
            indegree.put(node.id(), node.dependsOn().size());
            for (String dependency : node.dependsOn()) {
                outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node.id());
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            visited++;
            for (String child : outgoing.getOrDefault(id, List.of())) {
                int degree = indegree.computeIfPresent(child, (ignored, current) -> current - 1);
                if (degree == 0) queue.addLast(child);
            }
        }
        return visited == all.size() ? List.of() : List.of("dependency graph contains a cycle");
    }

    private static String uniqueId(DagTask workflow, java.util.Collection<String> pendingIds,
                                   int revision, String key, int index) {
        String slug = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) slug = "node-" + index;
        if (slug.length() > 40) slug = slug.substring(0, 40);
        String base = "v" + Math.max(1, revision) + "-" + slug;
        String id = base;
        int suffix = 2;
        Set<String> occupied = new HashSet<>(pendingIds);
        while (workflow.node(id) != null || occupied.contains(id)) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    public record CompilationResult(List<DagNode> nodes, List<String> errors) {
        public CompilationResult {
            nodes = nodes != null ? List.copyOf(nodes) : List.of();
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
        public static CompilationResult success(List<DagNode> nodes) {
            return new CompilationResult(nodes, List.of());
        }
        public static CompilationResult failure(List<String> errors) {
            return new CompilationResult(List.of(), errors);
        }
        public boolean valid() { return errors.isEmpty(); }
    }
}
