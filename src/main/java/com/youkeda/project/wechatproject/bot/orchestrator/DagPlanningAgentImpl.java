package com.youkeda.project.wechatproject.bot.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.context.AgentCapabilityView;
import com.youkeda.project.wechatproject.bot.context.CharacterContextTokenEstimator;
import com.youkeda.project.wechatproject.bot.context.ContextAudience;
import com.youkeda.project.wechatproject.bot.context.ContextBudget;
import com.youkeda.project.wechatproject.bot.context.ContextBuildRequest;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringProperties;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.context.DefaultContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.RuleBasedContextRelevanceClassifier;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.OpenAiCompatibleClient;
import com.youkeda.project.wechatproject.bot.tool.JsonExtractUtil;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import com.youkeda.project.wechatproject.bot.workflow.DagNodeDraft;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanDraft;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanningAgent;
import com.youkeda.project.wechatproject.bot.workflow.DagReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Model boundary for DAG planning. The backend remains authoritative for all runtime state. */
public class DagPlanningAgentImpl implements DagPlanningAgent, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DagPlanningAgentImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CAPABILITIES_LAYER_NOTE =
            "Agent capabilities are supplied in a separate system context layer.";

    private static final String DAG_PLAN_SYSTEM_PROMPT = """
            Create a compact task draft for a backend-owned DAG executor. Return one JSON object only.

            %s

            Rules:
            - Describe semantic tasks only. Backend code owns IDs, scheduling, retries, fallback and state.
            - Use only registered agent names. Use short unique node keys.
            - Every node instruction must be self-contained; no sub-agent receives conversation history.
            - context_note may contain only task-specific constraints, never a conversation transcript.
            - depends_on contains node keys. Independent nodes have no dependency and run in parallel.
            - Include the complete currently knowable workflow. Conditional future work may be added by reflection.
            - Use {{LAST_CHAT_TEXT}} when a downstream task consumes generated CHAT text.
            - Ask the user only when required information is genuinely missing.

            Schemas:
            {"status":"dag","reasoning":"...","nodes":[{"key":"write","agent":"CHAT","instruction":"...","depends_on":[],"context_note":"...","parameters":{}}]}
            {"status":"completed","reasoning":"...","final_reply":"..."}
            {"status":"ask_user","reasoning":"...","question":"..."}
            """;

    private static final String DAG_REFLECT_SYSTEM_PROMPT = """
            Review one completed DAG execution wave. Return one JSON object only.

            %s

            Backend code owns retries, state and graph validation. Choose only one action:
            - CONTINUE: the existing remaining graph is sufficient.
            - APPEND: add newly discovered downstream tasks.
            - RETRY: retry one failed node after semantic correction.
            - ROLLBACK: invalidate one node and its descendants, then rerun from it.
            - ASK_USER: required information or authorization is missing.
            - COMPLETE or PARTIAL_COMPLETE: stop with a concrete final reply.
            - When new user input changes a node that already ran, choose ROLLBACK and identify
              the earliest affected target_node. Do not APPEND a duplicate replacement branch.
            - Backend code checks completed side effects and asks for confirmation before rollback.

            Schema:
            {"action":"CONTINUE|APPEND|RETRY|ROLLBACK|ASK_USER|COMPLETE|PARTIAL_COMPLETE","target_node":"optional key or id","reason":"...","question":"...","final_reply":"...","new_nodes":[{"key":"...","agent":"REGISTERED_NAME","instruction":"...","depends_on":["existing-or-new-key"],"context_note":"...","parameters":{}}]}
            """;

    private final AiModelClient modelClient;
    private final String model;
    private final ContextEngineeringService contextEngineeringService;
    private final List<AgentCapabilityView> capabilityViews;
    private final ContextBudget contextBudget;
    private final long modelCallTimeoutMs;
    private final ExecutorService modelCallExecutor;

    public DagPlanningAgentImpl(AgentProperties properties, AgentRegistry registry,
                                SkillTools skillTools,
                                ContextEngineeringService contextEngineeringService) {
        this(OpenAiCompatibleClient.forIntent(properties), properties, registry,
                skillTools, contextEngineeringService);
    }

    public DagPlanningAgentImpl(AiModelClient modelClient, AgentProperties properties,
                                AgentRegistry registry, SkillTools skillTools) {
        this(modelClient, properties, registry, skillTools, null);
    }

    public DagPlanningAgentImpl(AiModelClient modelClient, AgentProperties properties,
                                AgentRegistry registry, SkillTools skillTools,
                                ContextEngineeringService contextEngineeringService) {
        this.modelClient = modelClient;
        this.model = properties.getIntentModel() != null && !properties.getIntentModel().isBlank()
                ? properties.getIntentModel() : properties.getModel();
        this.modelCallTimeoutMs = properties.getIntentReadTimeoutMs();
        this.modelCallExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.contextBudget = new ContextBudget(properties.getContextWindowTokens(), 0.2d);
        this.contextEngineeringService = contextEngineeringService != null
                ? contextEngineeringService
                : new DefaultContextEngineeringService(
                        new RuleBasedContextRelevanceClassifier(),
                        new CharacterContextTokenEstimator(),
                        ContextEngineeringProperties.defaults(),
                        contextBudget);
        this.capabilityViews = capabilityViews(registry, skillTools);
    }

    @Override
    public DagPlanDraft planDag(UserRequest request, List<String> validationErrors) {
        try {
            StringBuilder content = new StringBuilder(request.text() != null ? request.text() : "");
            appendValidationFeedback(content, validationErrors);
            ContextPackage context = buildContext(request, ContextStage.PLAN, content.toString(),
                    ContextTaskState.empty(), String.format(DAG_PLAN_SYSTEM_PROMPT, CAPABILITIES_LAYER_NOTE));
            return parseDagPlan(callModel(context, "PLAN"));
        } catch (Exception e) {
            log.warn("DAG planning failed: {}", e.getMessage());
            return DagPlanDraft.unavailable(e.getMessage());
        }
    }

    @Override
    public DagReflection reflectDag(UserRequest request, String compactSnapshot,
                                    List<String> validationErrors) {
        return reflectDag(request,
                new ContextTaskState(null, null, 0, null, null, compactSnapshot, List.of()),
                validationErrors);
    }

    @Override
    public DagReflection reflectDag(UserRequest request, ContextTaskState taskState,
                                    List<String> validationErrors) {
        try {
            StringBuilder content = new StringBuilder("Original goal:\n")
                    .append(request.text() != null ? request.text() : "");
            appendValidationFeedback(content, validationErrors);
            ContextPackage context = buildContext(request, ContextStage.REFLECT, content.toString(),
                    taskState, String.format(DAG_REFLECT_SYSTEM_PROMPT, CAPABILITIES_LAYER_NOTE));
            return parseDagReflection(callModel(context, "REFLECT"));
        } catch (Exception e) {
            log.warn("DAG reflection failed: {}", e.getMessage());
            return DagReflection.invalid(e.getMessage());
        }
    }

    private String callModel(ContextPackage context, String operation) throws IOException {
        long startedAt = System.nanoTime();
        log.info("[DAG-PLANNER] start operation={} model={} timeoutMs={} messages={} "
                        + "estimatedTokens={} tokenLimit={} compressions={}",
                operation, model, modelCallTimeoutMs, context.messages().size(),
                context.budgetReport().estimatedTokens(), context.budgetReport().inputTokenLimit(),
                context.compressionActions().size());

        // A socket read timeout does not cover DNS, connect and request upload. This deadline
        // bounds every network stage and response parsing as one planner operation.
        Future<String> call = modelCallExecutor.submit(() -> modelClient.chat(
                context.messages(), ChatCallOptions.deterministic(null, model, 4096)));
        try {
            String content = call.get(modelCallTimeoutMs, TimeUnit.MILLISECONDS);
            long latencyMs = elapsedMillis(startedAt);
            if (content == null || content.isBlank()) {
                log.warn("[DAG-PLANNER] empty operation={} model={} latencyMs={}",
                        operation, model, latencyMs);
                throw new IOException("empty content in DAG planner response");
            }
            log.info("[DAG-PLANNER] success operation={} model={} latencyMs={} responseChars={}",
                    operation, model, latencyMs, content.length());
            log.debug("DAG planner raw response: {}", content);
            return content;
        } catch (TimeoutException e) {
            call.cancel(true);
            long latencyMs = elapsedMillis(startedAt);
            log.warn("[DAG-PLANNER] timeout operation={} model={} timeoutMs={} latencyMs={}",
                    operation, model, modelCallTimeoutMs, latencyMs);
            throw new IOException("DAG planner timed out after " + modelCallTimeoutMs + " ms", e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[DAG-PLANNER] interrupted operation={} model={} latencyMs={}",
                    operation, model, elapsedMillis(startedAt));
            throw new IOException("DAG planner call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("[DAG-PLANNER] failure operation={} model={} latencyMs={} error={}",
                    operation, model, elapsedMillis(startedAt),
                    cause != null ? cause.getMessage() : e.getMessage());
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("DAG planner call failed", cause != null ? cause : e);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    @Override
    public void close() {
        modelCallExecutor.shutdownNow();
    }

    private ContextPackage buildContext(UserRequest request, ContextStage stage,
                                        String currentMessage, ContextTaskState taskState,
                                        String systemPrompt) {
        return contextEngineeringService.build(ContextBuildRequest.builder()
                .userId(request.userId())
                .currentMessage(currentMessage)
                .stage(stage)
                .audience(ContextAudience.ORCHESTRATOR)
                .recentHistory(request.history())
                .taskState(taskState)
                .agentCapabilities(capabilityViews)
                .fixedPromptMessages(List.of(new ChatRequest.Message("system", systemPrompt)))
                .includeCapabilityLayer(true)
                .imageBase64Urls(request.imageBase64Urls())
                .rememberedImageSummary(request.rememberedImageSummary())
                .budget(contextBudget)
                .build());
    }

    private static List<AgentCapabilityView> capabilityViews(AgentRegistry registry, SkillTools skillTools) {
        return registry.all().values().stream()
                .sorted(java.util.Comparator.comparing(unit -> unit.getName()))
                .map(unit -> {
                    var capability = unit.getCapability();
                    String description = capability.description();
                    if (skillTools != null) {
                        String skills = skillTools.getSkillsSummary(unit.getName());
                        if (skills != null && !skills.isBlank()) {
                            description += " Registered skills: " + truncate(skills, 500);
                        }
                    }
                    return new AgentCapabilityView(
                            unit.getName(), description, capability.strengths(), capability.outputType(),
                            capability.routingKeywords(), capability.directRouteEligible());
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private DagPlanDraft parseDagPlan(String content) {
        String json = extractJson(content);
        if (json == null) {
            return DagPlanDraft.invalid("planner returned no JSON object");
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            String status = stringValue(map.get("status")).toLowerCase(Locale.ROOT);
            String reasoning = stringValue(map.get("reasoning"));
            return switch (status) {
                case "dag" -> DagPlanDraft.dag(reasoning, parseDagNodes(map.get("nodes")));
                case "completed" -> DagPlanDraft.completed(reasoning, stringValue(map.get("final_reply")));
                case "ask_user", "needs_clarification" -> DagPlanDraft.askUser(
                        reasoning, stringValue(map.get("question")));
                default -> DagPlanDraft.invalid("unsupported planner status: " + status);
            };
        } catch (Exception e) {
            return DagPlanDraft.invalid("cannot parse planner response: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private DagReflection parseDagReflection(String content) {
        String json = extractJson(content);
        if (json == null) {
            return DagReflection.invalid("reflector returned no JSON object");
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            DagReflection.Action action;
            try {
                action = DagReflection.Action.valueOf(
                        stringValue(map.get("action")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                action = DagReflection.Action.INVALID;
            }
            return new DagReflection(
                    action,
                    stringValueOrNull(map.get("target_node")),
                    stringValueOrNull(map.get("reason")),
                    stringValueOrNull(map.get("question")),
                    stringValueOrNull(map.get("final_reply")),
                    parseDagNodes(map.get("new_nodes")));
        } catch (Exception e) {
            return DagReflection.invalid("cannot parse reflection response: " + e.getMessage());
        }
    }

    private List<DagNodeDraft> parseDagNodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<DagNodeDraft> nodes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String agent = stringValue(raw.get("agent"));
            if (agent.isBlank()) {
                agent = stringValue(raw.get("agent_type"));
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            if (raw.get("parameters") instanceof Map<?, ?> parameterMap) {
                for (Map.Entry<?, ?> entry : parameterMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        parameters.put(key, entry.getValue());
                    }
                }
            }
            nodes.add(new DagNodeDraft(
                    stringValue(raw.get("key")), agent, stringValue(raw.get("instruction")),
                    stringValueOrNull(raw.get("context_note")), stringList(raw.get("depends_on")),
                    parameters));
        }
        return nodes;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(item -> !item.isBlank()).toList();
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString().trim() : "";
    }

    private static String stringValueOrNull(Object value) {
        String string = stringValue(value);
        return string.isBlank() ? null : string;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private static void appendValidationFeedback(StringBuilder content, List<String> validationErrors) {
        if (validationErrors == null || validationErrors.isEmpty()) {
            return;
        }
        content.append("\n\nThe backend rejected the previous draft. Return a corrected object. Errors:\n");
        validationErrors.stream().limit(8)
                .forEach(error -> content.append("- ").append(error).append('\n'));
    }

    static String extractJson(String raw) {
        return JsonExtractUtil.extractJsonObject(raw);
    }
}
