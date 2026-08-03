package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.orchestrator.TaskScratchpad;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DefaultContextEngineeringService implements ContextEngineeringService {

    private final ContextRelevanceClassifier classifier;
    private final ContextTokenEstimator tokenEstimator;
    private final ContextEngineeringProperties properties;

    public DefaultContextEngineeringService(ContextRelevanceClassifier classifier,
                                            ContextTokenEstimator tokenEstimator) {
        this(classifier, tokenEstimator, ContextEngineeringProperties.defaults());
    }

    public DefaultContextEngineeringService(ContextRelevanceClassifier classifier,
                                            ContextTokenEstimator tokenEstimator,
                                            ContextEngineeringProperties properties) {
        this.classifier = classifier != null ? classifier : new RuleBasedContextRelevanceClassifier();
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : new CharacterContextTokenEstimator();
        this.properties = properties != null ? properties : ContextEngineeringProperties.defaults();
    }

    @Override
    public ContextPackage build(ContextBuildRequest request) {
        ContextBuildRequest safeRequest = request != null ? request : ContextBuildRequest.builder().build();
        ContextRelevance relevance = classifier.classify(safeRequest);
        List<ContextCompressionAction> actions = new ArrayList<>();
        List<ContextLayer> layers = new ArrayList<>();

        if (safeRequest.includeCapabilityLayer()) {
            addLayer(layers, "capabilities", 1, "system", capabilitiesPrompt(safeRequest.agentCapabilities()));
        }
        addLayer(layers, "image-context", 2, "system", imageContext(safeRequest));

        if (shouldIncludeHistory(relevance, safeRequest.stage())) {
            addHistoryLayers(layers, safeRequest.recentHistory(), actions);
        }
        if (shouldIncludeTaskState(relevance, safeRequest.stage())) {
            addScratchpadLayers(layers, safeRequest.scratchpad());
        }

        ContextBudget budget = safeRequest.budget() != null ? safeRequest.budget() : properties.toBudget();
        int limit = budget.inputTokenLimit();
        int estimated = estimate(layers, safeRequest.currentMessage(), safeRequest.fixedPromptMessages());
        if (estimated > limit) {
            estimated = compressUntilWithinBudget(layers, safeRequest.currentMessage(),
                    safeRequest.fixedPromptMessages(), limit, actions);
        }

        return new ContextPackage(
                toMessages(layers),
                relevance,
                new ContextBudgetReport(estimated, limit, estimated > limit),
                actions);
    }

    private void addHistoryLayers(List<ContextLayer> layers,
                                  List<ChatRequest.Message> rawHistory,
                                  List<ContextCompressionAction> actions) {
        if (rawHistory == null || rawHistory.isEmpty()) {
            return;
        }

        List<ChatRequest.Message> systemHistory = rawHistory.stream()
                .filter(message -> "system".equals(message.getRole()))
                .toList();
        for (ChatRequest.Message message : systemHistory) {
            addLayer(layers, "long-term-summary", 3, "system",
                    truncate(String.valueOf(message.getContent()), properties.getOlderHistorySummaryMaxChars()));
        }

        List<ChatRequest.Message> conversational = rawHistory.stream()
                .filter(message -> !"system".equals(message.getRole()))
                .toList();
        if (conversational.isEmpty()) {
            return;
        }

        int rawWindow = Math.min(properties.getRecentRawHistoryMessages(), conversational.size());
        int split = conversational.size() - rawWindow;
        if (split > 0) {
            String summary = summarizeHistory(conversational.subList(0, split));
            addLayer(layers, "conversation-summary", 3, "system", summary);
            actions.add(new ContextCompressionAction(
                    "conversation-history",
                    "sliding-summarize-old-history",
                    tokenEstimator.estimateMessages(conversational.subList(0, split)),
                    tokenEstimator.estimate(summary)));
        }
        for (ChatRequest.Message message : conversational.subList(split, conversational.size())) {
            layers.add(new ContextLayer("recent-history", 3,
                    message.getRole(), String.valueOf(message.getContent())));
        }
    }

    private void addScratchpadLayers(List<ContextLayer> layers, TaskScratchpad scratchpad) {
        if (scratchpad == null || scratchpad.isEmpty()) {
            return;
        }
        addLayer(layers, "task-checklist", 4, "system", taskChecklistPrompt(scratchpad));
        addLayer(layers, "tool-results", 5, "system",
                truncate(scratchpad.toReflectPrompt(), properties.getToolResultsMaxChars()));
    }

    private int compressUntilWithinBudget(List<ContextLayer> layers,
                                          String currentMessage,
                                          List<ChatRequest.Message> fixedPromptMessages,
                                          int limit,
                                          List<ContextCompressionAction> actions) {
        int estimated = estimate(layers, currentMessage, fixedPromptMessages);
        while (estimated > limit) {
            ContextLayer candidate = compressionCandidate(layers);
            if (candidate == null) {
                break;
            }
            int before = estimated;
            boolean changed = compressLayer(layers, candidate, limit);
            estimated = estimate(layers, currentMessage, fixedPromptMessages);
            if (changed) {
                actions.add(new ContextCompressionAction(
                        candidate.name(),
                        compressionActionName(candidate),
                        before,
                        estimated));
            } else {
                break;
            }
        }
        return estimated;
    }

    private ContextLayer compressionCandidate(List<ContextLayer> layers) {
        return layers.stream()
                .filter(layer -> layer.priority() > 1)
                .max(Comparator.comparingInt(ContextLayer::priority))
                .orElse(null);
    }

    private boolean compressLayer(List<ContextLayer> layers, ContextLayer layer, int limit) {
        int index = layers.indexOf(layer);
        if (index < 0) {
            return false;
        }

        if ("tool-results".equals(layer.name())) {
            String compressed = summarizeBlock("Tool and sub-agent results", layer.content(),
                    properties.getCompressedToolResultsMaxChars());
            if (compressed.length() < layer.content().length()) {
                layers.set(index, layer.withContent(compressed));
                return true;
            }
            layers.remove(index);
            return true;
        }

        if ("task-checklist".equals(layer.name())) {
            String compressed = summarizeBlock("Long task checklist", layer.content(),
                    Math.min(600, properties.getTaskStateMaxChars()));
            if (compressed.length() < layer.content().length()) {
                layers.set(index, layer.withContent(compressed));
                return true;
            }
            layers.remove(index);
            return true;
        }

        if ("conversation-summary".equals(layer.name()) || "long-term-summary".equals(layer.name())) {
            String compressed = summarizeBlock("Compressed context", layer.content(), 500);
            if (compressed.length() < layer.content().length() && tokenEstimator.estimate(compressed) < limit) {
                layers.set(index, layer.withContent(compressed));
                return true;
            }
            layers.remove(index);
            return true;
        }

        if ("recent-history".equals(layer.name())) {
            layers.remove(index);
            return true;
        }

        if (layer.content().length() > 300) {
            layers.set(index, layer.withContent(truncate(layer.content(), 300)));
            return true;
        }
        layers.remove(index);
        return true;
    }

    private static String compressionActionName(ContextLayer layer) {
        return switch (layer.name()) {
            case "tool-results" -> "summarize-or-drop-tool-results";
            case "task-checklist" -> "summarize-task-checklist";
            case "conversation-summary", "long-term-summary" -> "compress-summary";
            case "recent-history" -> "drop-oldest-recent-history";
            default -> "compress-layer";
        };
    }

    private int estimate(List<ContextLayer> layers, String currentMessage,
                         List<ChatRequest.Message> fixedPromptMessages) {
        int total = tokenEstimator.estimate(currentMessage) + 4;
        total += tokenEstimator.estimateMessages(fixedPromptMessages);
        total += tokenEstimator.estimateMessages(toMessages(layers));
        return total;
    }

    private static List<ChatRequest.Message> toMessages(List<ContextLayer> layers) {
        return layers.stream()
                .filter(layer -> layer.content() != null && !layer.content().isBlank())
                .map(layer -> new ChatRequest.Message(layer.role(), layer.content()))
                .toList();
    }

    private static void addLayer(List<ContextLayer> layers, String name, int priority, String role, String content) {
        if (content != null && !content.isBlank()) {
            layers.add(new ContextLayer(name, priority, role, content));
        }
    }

    private static boolean shouldIncludeHistory(ContextRelevance relevance, ContextStage stage) {
        return stage == ContextStage.REFLECT
                || stage == ContextStage.RESUME
                || relevance == ContextRelevance.RELATED
                || relevance == ContextRelevance.CONTINUATION
                || relevance == ContextRelevance.RESUME_TASK
                || relevance == ContextRelevance.TOOL_DEPENDENT;
    }

    private static boolean shouldIncludeTaskState(ContextRelevance relevance, ContextStage stage) {
        return stage == ContextStage.REFLECT
                || stage == ContextStage.RESUME
                || relevance == ContextRelevance.RELATED
                || relevance == ContextRelevance.CONTINUATION
                || relevance == ContextRelevance.RESUME_TASK
                || relevance == ContextRelevance.TOOL_DEPENDENT;
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
            sb.append("  output: ").append(blankToPlaceholder(capability.outputType())).append("\n");
        }
        return sb.toString().trim();
    }

    private static String imageContext(ContextBuildRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.imageBase64Urls() != null && !request.imageBase64Urls().isEmpty()) {
            sb.append("[user attached images: ").append(request.imageBase64Urls().size()).append("]");
        }
        if (request.rememberedImageSummary() != null && !request.rememberedImageSummary().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("[remembered image summary] ").append(request.rememberedImageSummary());
        }
        return sb.toString();
    }

    private static String taskChecklistPrompt(TaskScratchpad scratchpad) {
        StringBuilder sb = new StringBuilder();
        sb.append("Long task checklist:\n");
        int index = 1;
        for (TaskScratchpad.ExecutionRecord record : scratchpad.records()) {
            sb.append(index++).append(". ")
                    .append(record.task().agentType())
                    .append(" - ")
                    .append(record.result().status())
                    .append(" - ")
                    .append(truncate(record.task().instruction(), 160));
            if (record.result().isPaused()) {
                sb.append(" - waiting for user");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
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
                    .append(truncate(content, 220))
                    .append("\n");
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

    private record ContextLayer(String name, int priority, String role, String content) {
        ContextLayer withContent(String newContent) {
            return new ContextLayer(name, priority, role, newContent);
        }
    }
}
