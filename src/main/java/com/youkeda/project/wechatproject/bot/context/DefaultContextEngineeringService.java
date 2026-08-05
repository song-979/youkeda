package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Default layered context assembler shared by the orchestrator and model-backed agents. */
public class DefaultContextEngineeringService implements ContextEngineeringService {

    private static final int REQUIRED_PRIORITY = 100;

    private final ContextRelevanceClassifier classifier;
    private final ContextTokenEstimator tokenEstimator;
    private final ContextEngineeringProperties properties;
    private final ContextBudget defaultBudget;

    public DefaultContextEngineeringService(ContextRelevanceClassifier classifier,
                                            ContextTokenEstimator tokenEstimator) {
        this(classifier, tokenEstimator, ContextEngineeringProperties.defaults(), ContextBudget.defaults());
    }

    public DefaultContextEngineeringService(ContextRelevanceClassifier classifier,
                                            ContextTokenEstimator tokenEstimator,
                                            ContextEngineeringProperties properties) {
        this(classifier, tokenEstimator, properties, ContextBudget.defaults());
    }

    public DefaultContextEngineeringService(ContextRelevanceClassifier classifier,
                                            ContextTokenEstimator tokenEstimator,
                                            ContextEngineeringProperties properties,
                                            ContextBudget defaultBudget) {
        this.classifier = classifier != null ? classifier : new RuleBasedContextRelevanceClassifier();
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : new CharacterContextTokenEstimator();
        this.properties = properties != null ? properties : ContextEngineeringProperties.defaults();
        this.defaultBudget = defaultBudget != null ? defaultBudget : ContextBudget.defaults();
    }

    @Override
    public ContextPackage build(ContextBuildRequest request) {
        ContextBuildRequest safeRequest = request != null ? request : ContextBuildRequest.builder().build();
        ContextRelevance relevance = classifier.classify(safeRequest);
        List<ContextCompressionAction> actions = new ArrayList<>();
        List<ContextLayer> layers = new ArrayList<>();

        addFixedPrompts(layers, safeRequest.fixedPromptMessages());
        if (safeRequest.includeCapabilityLayer()) {
            addLayer(layers, "capabilities", 20, true, "system",
                    capabilitiesPrompt(safeRequest.agentCapabilities()));
        }
        addLayer(layers, "image-context", 30, true, "system", imageContext(safeRequest));
        addLayer(layers, "agent-memory", 35, true, "system", safeRequest.agentMemorySummary());

        if (shouldIncludeHistory(relevance, safeRequest)) {
            addHistoryLayers(layers, safeRequest.recentHistory(), actions);
        }
        if (shouldIncludeTaskState(relevance, safeRequest)) {
            addTaskStateLayers(layers, safeRequest.taskState(), safeRequest.stage());
        }

        String currentMessage = currentMessage(safeRequest);
        addLayer(layers, "current-message", REQUIRED_PRIORITY, false, "user", currentMessage);

        ContextBudget budget = safeRequest.budget() != null ? safeRequest.budget() : defaultBudget;
        int limit = budget.inputTokenLimit();
        int estimated = estimate(layers);
        if (estimated > limit) {
            estimated = compressUntilWithinBudget(layers, limit, actions);
        }

        return new ContextPackage(
                toMessages(layers),
                relevance,
                new ContextBudgetReport(estimated, limit, estimated > limit),
                actions);
    }

    private static void addFixedPrompts(List<ContextLayer> layers,
                                        List<ChatRequest.Message> fixedPromptMessages) {
        if (fixedPromptMessages == null) {
            return;
        }
        int index = 0;
        for (ChatRequest.Message message : fixedPromptMessages) {
            if (message == null) {
                continue;
            }
            addLayer(layers, "fixed-prompt-" + index++, REQUIRED_PRIORITY, false,
                    message.getRole(), String.valueOf(message.getContent()));
        }
    }

    private void addHistoryLayers(List<ContextLayer> layers,
                                  List<ChatRequest.Message> rawHistory,
                                  List<ContextCompressionAction> actions) {
        if (rawHistory == null || rawHistory.isEmpty()) {
            return;
        }

        rawHistory.stream()
                .filter(message -> message != null && "system".equalsIgnoreCase(message.getRole()))
                .forEach(message -> addLayer(layers, "long-term-summary", 30, true, "system",
                        truncate(String.valueOf(message.getContent()),
                                properties.getOlderHistorySummaryMaxChars())));

        List<ChatRequest.Message> conversational = rawHistory.stream()
                .filter(message -> message != null && !"system".equalsIgnoreCase(message.getRole()))
                .toList();
        if (conversational.isEmpty()) {
            return;
        }

        int rawWindow = Math.min(properties.getRecentRawHistoryMessages(), conversational.size());
        int split = conversational.size() - rawWindow;
        if (split > 0) {
            String summary = summarizeHistory(conversational.subList(0, split));
            addLayer(layers, "conversation-summary", 25, true, "system", summary);
            actions.add(new ContextCompressionAction(
                    "conversation-history",
                    "sliding-summarize-old-history",
                    tokenEstimator.estimateMessages(conversational.subList(0, split)),
                    tokenEstimator.estimate(summary)));
        }
        for (ChatRequest.Message message : conversational.subList(split, conversational.size())) {
            layers.add(new ContextLayer("recent-history", 60, true,
                    message.getRole(), String.valueOf(message.getContent())));
        }
    }

    private void addTaskStateLayers(List<ContextLayer> layers,
                                    ContextTaskState taskState,
                                    ContextStage stage) {
        if (taskState == null || taskState.isEmpty()) {
            return;
        }
        int statePriority = switch (stage) {
            case REFLECT, RESUME, EXECUTE -> 85;
            default -> 50;
        };
        addLayer(layers, "task-state", statePriority, true, "system", taskStatePrompt(taskState));

        int resultPriority = switch (stage) {
            case REFLECT, RESUME, EXECUTE -> 90;
            default -> 55;
        };
        for (ContextTaskRecord record : taskState.records()) {
            String result = taskResultPrompt(record);
            addLayer(layers, "execution-result:" + blankToPlaceholder(record.id()),
                    resultPriority, true, "system", result);
        }
    }

    private int compressUntilWithinBudget(List<ContextLayer> layers,
                                          int limit,
                                          List<ContextCompressionAction> actions) {
        int estimated = estimate(layers);
        while (estimated > limit) {
            ContextLayer candidate = compressionCandidate(layers);
            if (candidate == null) {
                break;
            }
            int before = estimated;
            String action = compressLayer(layers, candidate);
            estimated = estimate(layers);
            actions.add(new ContextCompressionAction(candidate.name(), action, before, estimated));
        }
        return estimated;
    }

    private static ContextLayer compressionCandidate(List<ContextLayer> layers) {
        return layers.stream()
                .filter(ContextLayer::compressible)
                .min(Comparator.comparingInt(ContextLayer::retentionPriority))
                .orElse(null);
    }

    private String compressLayer(List<ContextLayer> layers, ContextLayer layer) {
        int index = layers.indexOf(layer);
        if (index < 0) {
            return "skip-missing-layer";
        }

        if ("capabilities".equals(layer.name())) {
            String compact = compactCapabilities(layer.content());
            layers.set(index, layer.compressed(compact, false));
            return "compact-agent-capabilities";
        }
        if ("task-state".equals(layer.name())) {
            String compact = summarizeBlock("Active DAG state", layer.content(),
                    Math.min(800, properties.getTaskStateMaxChars()));
            layers.set(index, layer.compressed(compact, false));
            return "compact-dag-state";
        }
        if (layer.name().startsWith("execution-result:")) {
            String compact = summarizeBlock("Dependency result", layer.content(),
                    properties.getCompressedToolResultsMaxChars());
            layers.set(index, layer.compressed(compact, false));
            return "compact-execution-result";
        }
        if ("recent-history".equals(layer.name())) {
            layers.remove(index);
            return "drop-oldest-recent-history";
        }
        if ("conversation-summary".equals(layer.name())
                || "long-term-summary".equals(layer.name())
                || "agent-memory".equals(layer.name())) {
            if (layer.content().length() > 500) {
                layers.set(index, layer.compressed(
                        summarizeBlock("Compressed context", layer.content(), 500), true));
                return "compress-summary";
            }
            layers.remove(index);
            return "drop-low-priority-summary";
        }
        if (layer.content().length() > 300) {
            layers.set(index, layer.compressed(truncate(layer.content(), 300), true));
            return "truncate-layer";
        }
        layers.remove(index);
        return "drop-layer";
    }

    private int estimate(List<ContextLayer> layers) {
        return tokenEstimator.estimateMessages(toMessages(layers));
    }

    private static List<ChatRequest.Message> toMessages(List<ContextLayer> layers) {
        return layers.stream()
                .filter(layer -> layer.content() != null && !layer.content().isBlank())
                .map(layer -> new ChatRequest.Message(layer.role(), layer.content()))
                .toList();
    }

    private static void addLayer(List<ContextLayer> layers, String name, int retentionPriority,
                                 boolean compressible, String role, String content) {
        if (content != null && !content.isBlank()) {
            layers.add(new ContextLayer(name, retentionPriority, compressible, role, content));
        }
    }

    private static boolean shouldIncludeHistory(ContextRelevance relevance,
                                                ContextBuildRequest request) {
        return request.stage() == ContextStage.REFLECT
                || request.stage() == ContextStage.RESUME
                || request.stage() == ContextStage.SCHEDULED
                || request.stage() == ContextStage.HEARTBEAT
                || request.audience() == ContextAudience.DIRECT
                || relevance == ContextRelevance.RELATED
                || relevance == ContextRelevance.CONTINUATION
                || relevance == ContextRelevance.RESUME_TASK
                || relevance == ContextRelevance.TOOL_DEPENDENT;
    }

    private static boolean shouldIncludeTaskState(ContextRelevance relevance,
                                                  ContextBuildRequest request) {
        if (request.taskState() == null || request.taskState().isEmpty()) {
            return false;
        }
        return request.audience() == ContextAudience.SUB_AGENT
                || request.stage() == ContextStage.REFLECT
                || request.stage() == ContextStage.RESUME
                || request.stage() == ContextStage.EXECUTE
                || relevance == ContextRelevance.RESUME_TASK
                || relevance == ContextRelevance.TOOL_DEPENDENT
                || relevance == ContextRelevance.CONTINUATION;
    }

    private static String capabilitiesPrompt(List<AgentCapabilityView> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Available agent units:\n");
        for (AgentCapabilityView capability : capabilities) {
            sb.append("- ").append(blankToPlaceholder(capability.name()))
                    .append(": ").append(blankToPlaceholder(capability.description())).append("\n");
            if (!capability.strengths().isEmpty()) {
                sb.append("  strengths: ").append(String.join(", ", capability.strengths())).append("\n");
            }
            if (!capability.routingKeywords().isEmpty()) {
                sb.append("  triggers: ").append(String.join(", ", capability.routingKeywords())).append("\n");
            }
            sb.append("  direct-route: ").append(capability.directRouteEligible()).append("\n");
            sb.append("  output: ").append(blankToPlaceholder(capability.outputType())).append("\n");
        }
        return sb.toString().trim();
    }

    private static String compactCapabilities(String content) {
        StringBuilder compact = new StringBuilder("Available agents (compact):\n");
        for (String line : content.split("\\R")) {
            if (line.startsWith("- ")) {
                compact.append(line).append('\n');
            }
        }
        return compact.toString().trim();
    }

    private static String imageContext(ContextBuildRequest request) {
        StringBuilder sb = new StringBuilder();
        if (!request.imageBase64Urls().isEmpty()) {
            sb.append("[attached images: ").append(request.imageBase64Urls().size()).append("]");
        }
        if (request.rememberedImageSummary() != null && !request.rememberedImageSummary().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("[remembered image summary] ").append(request.rememberedImageSummary());
        }
        return sb.toString();
    }

    private static String currentMessage(ContextBuildRequest request) {
        if (request.currentMessage() != null && !request.currentMessage().isBlank()) {
            return request.currentMessage();
        }
        return request.imageBase64Urls().isEmpty() ? "" : "[The user supplied image input.]";
    }

    private static String taskStatePrompt(ContextTaskState state) {
        StringBuilder sb = new StringBuilder("Active DAG state:\n");
        if (state.dagId() != null) {
            sb.append("dagId=").append(state.dagId());
            if (state.dagStatus() != null) {
                sb.append(" status=").append(state.dagStatus());
            }
            sb.append(" revision=").append(state.revision()).append('\n');
        }
        if (state.currentNodeId() != null) {
            sb.append("current_node=").append(state.currentNodeId()).append('\n');
        }
        if (state.latestUserInput() != null && !state.latestUserInput().isBlank()) {
            sb.append("latest_user_input=").append(truncate(state.latestUserInput(), 500)).append('\n');
        }
        if (state.summary() != null && !state.summary().isBlank()) {
            sb.append("summary=").append(truncate(state.summary(), 1_000)).append('\n');
        }
        for (ContextTaskRecord record : state.records()) {
            sb.append("- ").append(blankToPlaceholder(record.id()))
                    .append(" key=").append(blankToPlaceholder(record.key()))
                    .append(" agent=").append(blankToPlaceholder(record.agentType()))
                    .append(" status=").append(blankToPlaceholder(record.status()))
                    .append(" depends_on=").append(record.dependsOn());
            if (record.instruction() != null && !record.instruction().isBlank()) {
                sb.append(" instruction=").append(truncate(sanitize(record.instruction()), 220));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String taskResultPrompt(ContextTaskRecord record) {
        if ((record.result() == null || record.result().isBlank())
                && (record.error() == null || record.error().isBlank())
                && (record.messageToUser() == null || record.messageToUser().isBlank())
                && record.signals().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("DAG node result: ")
                .append(blankToPlaceholder(record.id()))
                .append(" agent=").append(blankToPlaceholder(record.agentType()))
                .append(" status=").append(blankToPlaceholder(record.status())).append('\n');
        if (record.result() != null && !record.result().isBlank()) {
            sb.append("result=").append(truncate(record.result(), properties.getToolResultsMaxChars())).append('\n');
        }
        if (record.error() != null && !record.error().isBlank()) {
            sb.append("error=").append(truncate(record.error(), 500)).append('\n');
        }
        if (record.messageToUser() != null && !record.messageToUser().isBlank()) {
            sb.append("waiting_for_user=").append(truncate(record.messageToUser(), 500)).append('\n');
        }
        if (!record.signals().isEmpty()) {
            sb.append("signals=").append(formatSignals(record.signals())).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatSignals(Map<String, String> signals) {
        return signals.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String summarizeHistory(List<ChatRequest.Message> messages) {
        StringBuilder sb = new StringBuilder("Earlier conversation summary:\n");
        int index = 1;
        for (ChatRequest.Message message : messages) {
            String content = sanitize(String.valueOf(message.getContent()));
            if (content.isBlank()) {
                continue;
            }
            sb.append("- ").append(index++).append(". ")
                    .append(message.getRole()).append(": ")
                    .append(truncate(content, 220)).append("\n");
            if (sb.length() >= properties.getOlderHistorySummaryMaxChars()) {
                break;
            }
        }
        return truncate(sb.toString().trim(), properties.getOlderHistorySummaryMaxChars());
    }

    private static String summarizeBlock(String title, String content, int maxChars) {
        String clean = sanitize(content);
        if (clean.length() <= maxChars) {
            return clean;
        }
        return title + " summary:\n" + truncate(clean, Math.max(60, maxChars - title.length() - 20));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...";
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(unspecified)" : value;
    }

    private record ContextLayer(String name, int retentionPriority, boolean compressible,
                                String role, String content) {
        ContextLayer compressed(String newContent, boolean mayCompressAgain) {
            return new ContextLayer(name, retentionPriority, mayCompressAgain, role, newContent);
        }
    }
}
