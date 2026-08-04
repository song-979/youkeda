package com.youkeda.project.wechatproject.bot.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Renders a compact, privacy-safe DAG topology for multiline console logs. */
public final class DagConsoleRenderer {

    private static final int NODE_BOX_INNER_WIDTH = 34;
    private static final int EDGE_BOX_INNER_WIDTH = 24;
    private static final int MAX_NODES_PER_ROW = 3;
    private static final String NODE_GAP = "    ";

    private DagConsoleRenderer() {
    }

    public static String render(DagTask task) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        Map<Integer, List<DagNode>> stages = new TreeMap<>();
        for (DagNode node : task.nodes()) {
            int level = levelOf(task, node, levels);
            stages.computeIfAbsent(level, ignored -> new ArrayList<>()).add(node);
        }

        StringBuilder graph = new StringBuilder();
        graph.append("DAG dagId=").append(task.dagId())
                .append(" revision=").append(task.revision())
                .append(" status=").append(task.status())
                .append(" nodes=").append(task.nodes().size()).append('\n');
        graph.append("TOPOLOGY (topological stages)\n");

        if (stages.isEmpty()) {
            appendEmptyGraph(graph);
        }

        for (Map.Entry<Integer, List<DagNode>> entry : stages.entrySet()) {
            List<DagNode> nodes = entry.getValue();
            graph.append("stage ").append(entry.getKey() + 1)
                    .append(nodes.size() > 1 ? " [PARALLEL x" : " [SERIAL x")
                    .append(nodes.size()).append("]\n");
            appendNodeGrid(graph, nodes);
        }

        graph.append("DIRECTED EDGES (dependency ----> dependent)\n");
        boolean hasEdges = false;
        for (DagNode node : task.nodes()) {
            for (String dependency : node.dependsOn()) {
                hasEdges = true;
                appendDirectedEdge(graph, dependency, node.id());
            }
        }
        if (!hasEdges) {
            graph.append("(none)\n");
        }
        return graph.toString().stripTrailing();
    }

    private static void appendEmptyGraph(StringBuilder graph) {
        String message = "graph not compiled";
        graph.append(boxBorder(NODE_BOX_INNER_WIDTH)).append('\n')
                .append(boxContent(message, NODE_BOX_INNER_WIDTH)).append('\n')
                .append(boxBorder(NODE_BOX_INNER_WIDTH)).append('\n');
    }

    private static void appendNodeGrid(StringBuilder graph, List<DagNode> nodes) {
        for (int offset = 0; offset < nodes.size(); offset += MAX_NODES_PER_ROW) {
            List<DagNode> row = nodes.subList(offset, Math.min(offset + MAX_NODES_PER_ROW, nodes.size()));
            List<List<String>> boxes = row.stream().map(DagConsoleRenderer::nodeBox).toList();
            for (int line = 0; line < boxes.getFirst().size(); line++) {
                for (int box = 0; box < boxes.size(); box++) {
                    if (box > 0) graph.append(NODE_GAP);
                    graph.append(boxes.get(box).get(line));
                }
                graph.append('\n');
            }
        }
    }

    private static List<String> nodeBox(DagNode node) {
        String key = safe(node.key(), 14);
        String title = safe(node.id(), 17) + (key.isBlank() ? "" : " [" + key + "]");
        String state = safe(node.agentType(), 10) + " | " + node.status()
                + " | try " + node.attemptCount() + "/" + node.maxAttempts();
        return List.of(
                boxBorder(NODE_BOX_INNER_WIDTH),
                boxContent(title, NODE_BOX_INNER_WIDTH),
                boxContent(state, NODE_BOX_INNER_WIDTH),
                boxBorder(NODE_BOX_INNER_WIDTH));
    }

    private static void appendDirectedEdge(StringBuilder graph, String sourceId, String targetId) {
        String border = boxBorder(EDGE_BOX_INNER_WIDTH);
        graph.append(border).append("       ").append(border).append('\n')
                .append(boxContent(safe(sourceId, EDGE_BOX_INNER_WIDTH), EDGE_BOX_INNER_WIDTH))
                .append(" ----> ")
                .append(boxContent(safe(targetId, EDGE_BOX_INNER_WIDTH), EDGE_BOX_INNER_WIDTH))
                .append('\n')
                .append(border).append("       ").append(border).append('\n');
    }

    private static String boxBorder(int innerWidth) {
        return "+" + "-".repeat(innerWidth + 2) + "+";
    }

    private static String boxContent(String value, int innerWidth) {
        String clipped = safe(value, innerWidth);
        return "| " + clipped + " ".repeat(innerWidth - clipped.length()) + " |";
    }

    private static int levelOf(DagTask task, DagNode node, Map<String, Integer> levels) {
        Integer cached = levels.get(node.id());
        if (cached != null) return cached;
        int level = 0;
        for (String dependencyId : node.dependsOn()) {
            DagNode dependency = task.node(dependencyId);
            if (dependency != null) {
                level = Math.max(level, levelOf(task, dependency, levels) + 1);
            }
        }
        levels.put(node.id(), level);
        return level;
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        if (sanitized.length() <= maxLength) return sanitized;
        if (maxLength <= 3) return sanitized.substring(0, maxLength);
        return sanitized.substring(0, maxLength - 3) + "...";
    }
}
