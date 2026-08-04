package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentExecutionContext;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.DagOrchestrationProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Backend-owned DAG Lite runtime with deterministic scheduling and bounded LLM repair/reflection. */
public class DagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(DagOrchestrationService.class);
    private static final int MAX_INLINE_TEXT_CHARS = 2_500;
    private static final Pattern DAG_ID_PATTERN = Pattern.compile("(?i)D-[A-Z0-9]{8}");

    private final DagPlanningAgent planner;
    private final AgentRegistry registry;
    private final DagTaskStore store;
    private final DagCompiler compiler;
    private final FailurePolicy failurePolicy = new FailurePolicy();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, DagTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runners = new ConcurrentHashMap<>();
    private final Map<String, UserRequest> latestRequests = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> progressCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<DagRunOutcome>> completionCallbacks = new ConcurrentHashMap<>();
    private final int maxWaves;
    private final int maxExecutionSeconds;
    private final int maxReplans;
    private final int planRepairAttempts;
    private final int nodeTimeoutSeconds;
    private final boolean reflectionEnabled;

    public DagOrchestrationService(DagPlanningAgent planner, AgentRegistry registry,
                                   DagTaskStore store, DagOrchestrationProperties properties) {
        this.planner = planner;
        this.registry = registry;
        this.store = store;
        this.compiler = new DagCompiler(registry, properties.getDagMaxNodes(),
                properties.getDagDefaultMaxAttempts());
        this.maxWaves = Math.max(1, properties.getDagMaxWaves());
        this.maxExecutionSeconds = Math.max(30, properties.getMaxExecutionSeconds());
        this.maxReplans = Math.max(0, properties.getDagMaxReplans());
        this.planRepairAttempts = Math.max(0, properties.getDagPlanRepairAttempts());
        this.nodeTimeoutSeconds = Math.max(1, properties.getDagNodeTimeoutSeconds());
        this.reflectionEnabled = properties.isReflectionEnabled();
        store.findActive().forEach(task -> activeTasks.put(task.dagId(), task));
    }

    public boolean hasActiveDag() {
        return focusedTask().isPresent();
    }

    public Optional<ContextTaskState> focusedContext() {
        return focusedTask().map(task -> DagContextMapper.forOrchestrator(task, null));
    }

    public List<DagTask> activeDags() {
        return store.findActive();
    }

    /** Starts planning on a background virtual thread and returns before any model or agent call completes. */
    public DagSubmission submit(UserRequest request, Consumer<String> progressCallback,
                                Consumer<DagRunOutcome> completionCallback) {
        if (request == null) return DagSubmission.unavailable("请求为空");
        DagTask task = new DagTask(newDagId(), request.userId(), request.text());
        task.setStatus(DagTask.Status.PLANNING);
        focus(task);
        rememberCallbacks(task.dagId(), request, progressCallback, completionCallback);
        log.info("[DAG] accepted dagId={} state=PLANNING activeDags={}",
                task.dagId(), store.findActive().size());
        logGraph(task, "accepted", "GRAPH_PENDING");
        schedule(task, true);
        return DagSubmission.accepted(task.dagId(), "已创建 DAG 任务 " + task.dagId() + "，正在规划。");
    }

    /** Applies one user message to the focused or explicitly addressed DAG task. */
    public DagSubmission handleUserMessage(UserRequest request, Consumer<String> progressCallback,
                                           Consumer<DagRunOutcome> completionCallback) {
        if (request == null || request.text() == null) return DagSubmission.noActive();
        if (isListCommand(request.text())) {
            return DagSubmission.controlled(null, formatTaskList());
        }
        DagTask task = resolveTask(request.text()).orElse(null);
        if (task == null) {
            return isDagControlMessage(request.text())
                    ? DagSubmission.controlled(null, "没有找到可操作的 DAG 任务。")
                    : DagSubmission.noActive();
        }
        String input = stripDagId(request.text()).trim();
        rememberCallbacks(task.dagId(), request, progressCallback, completionCallback);
        synchronized (task) {
            if (isFocusCommand(input)) {
                focus(task);
                return DagSubmission.controlled(task.dagId(), "已切换到 DAG 任务 " + task.dagId() + "。");
            }
            if (isPauseCommand(input)) {
                if (task.status() == DagTask.Status.PAUSED || task.status() == DagTask.Status.PAUSE_REQUESTED) {
                    return DagSubmission.controlled(task.dagId(), "DAG 任务 " + task.dagId() + " 已经暂停。");
                }
                task.setStatus(runners.containsKey(task.dagId())
                        ? DagTask.Status.PAUSE_REQUESTED : DagTask.Status.PAUSED);
                store.save(task);
                logGraph(task, "pause-requested");
                return DagSubmission.controlled(task.dagId(), task.status() == DagTask.Status.PAUSED
                        ? "DAG 任务 " + task.dagId() + " 已暂停。"
                        : "已收到暂停请求，当前节点安全结束后暂停 DAG 任务 " + task.dagId() + "。");
            }
            if (isCancelCommand(input)) {
                boolean hasRunner = runners.containsKey(task.dagId());
                task.setStatus(DagTask.Status.CANCELLED);
                task.setFinalReply("任务已由用户取消。");
                store.save(task);
                logGraph(task, "cancelled");
                if (!hasRunner) releaseTerminalTask(task);
                return DagSubmission.controlled(task.dagId(), "已取消 DAG 任务 " + task.dagId() + "。");
            }
            if (isResumeCommand(input)) {
                if (task.status() == DagTask.Status.WAITING_USER && task.pendingUserInputs().isEmpty()) {
                    return DagSubmission.controlled(task.dagId(), nonBlank(task.waitMessage(), "请先补充所需信息。"));
                }
                task.setStatus(DagTask.Status.RUNNING);
                store.save(task);
                logGraph(task, "resumed");
                schedule(task, task.nodes().isEmpty());
                return DagSubmission.controlled(task.dagId(), "已继续 DAG 任务 " + task.dagId() + "。");
            }

            DagNode pendingRollback = pendingRollbackNode(task);
            if (pendingRollback != null) {
                if (isRollbackConfirmation(input)) {
                    resetRollbackBranch(task, pendingRollback);
                    store.save(task);
                    logGraph(task, "rollback-confirmed");
                    schedule(task, false);
                    return DagSubmission.controlled(task.dagId(),
                            "已确认回退，将从步骤“" + pendingRollback.key() + "”继续执行。");
                }
                if (isRollbackCancellation(input)) {
                    pendingRollback.setResult(withoutSignal(
                            pendingRollback.result(), "dag.pendingRollback"));
                    pendingRollback.setStatus(DagNode.Status.SUCCEEDED);
                    task.setStatus(DagTask.Status.RUNNING);
                    task.setWaitMessage(null);
                    store.save(task);
                    schedule(task, false);
                    return DagSubmission.controlled(task.dagId(),
                            "已取消回退，保留步骤“" + pendingRollback.key() + "”的现有结果。");
                }
                return DagSubmission.controlled(task.dagId(), nonBlank(task.waitMessage(),
                        "该修改会回退已执行的副作用步骤，请回复“确认回退”或“取消回退”。"));
            }

            task.addPendingUserInput(input);
            if (task.status() == DagTask.Status.WAITING_USER) {
                resumeWaitingTask(task);
                store.save(task);
                schedule(task, task.nodes().isEmpty());
                return DagSubmission.controlled(task.dagId(), "已收到补充信息，继续 DAG 任务 " + task.dagId() + "。");
            }
            store.save(task);
            if (task.status() == DagTask.Status.PAUSED || task.status() == DagTask.Status.PAUSE_REQUESTED) {
                return DagSubmission.controlled(task.dagId(), "已记录对 DAG 任务 " + task.dagId() + " 的修改，继续时应用。");
            }
            if (!runners.containsKey(task.dagId())) {
                schedule(task, task.nodes().isEmpty());
            }
            return DagSubmission.controlled(task.dagId(), "已记录新要求，将在当前执行轮次结束后更新 DAG。" );
        }
    }

    private void schedule(DagTask task, boolean needsPlanning) {
        Future<?> running = runners.get(task.dagId());
        if (running != null && !running.isDone()) return;
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> runAsync(task, needsPlanning), executor);
        runners.put(task.dagId(), future);
        future.whenComplete((ignored, error) -> {
            runners.remove(task.dagId(), future);
            // Resume can race with the old runner's final checkpoint. Re-dispatch only when
            // backend state says there is still runnable work.
            if (task.status() == DagTask.Status.RUNNING && task.hasPendingWork()) {
                schedule(task, task.nodes().isEmpty());
            }
        });
    }

    private void runAsync(DagTask task, boolean needsPlanning) {
        try {
            UserRequest request = latestRequests.get(task.dagId());
            if (request == null) {
                request = new UserRequest(task.recipientId(), task.originalText(), List.of(), List.of());
            }
            Consumer<String> progress = progressCallbacks.get(task.dagId());
            if (needsPlanning || task.nodes().isEmpty()) {
                int plannedInputRevision;
                UserRequest planningRequest;
                synchronized (task) {
                    plannedInputRevision = task.inputRevision();
                    planningRequest = planningRequest(task, request);
                }
                PlanResult planned = planTask(task, planningRequest);
                if (planned.directOutcome() != null) {
                    completeAsync(task, planned.directOutcome());
                    return;
                }
                if (planned.task() == null) {
                    completeAsync(task, DagRunOutcome.completed(nonBlank(task.finalReply(),
                            "复杂任务规划暂时不可用，请稍后重试。")));
                    return;
                }
                synchronized (task) {
                    // Only clear inputs that were included in this exact planning snapshot.
                    if (task.inputRevision() == plannedInputRevision) {
                        task.consumePendingUserInputs();
                        store.save(task);
                    }
                }
                request = planningRequest;
            }
            DagRunOutcome outcome = run(task, request, null, progress);
            completeAsync(task, outcome);
        } catch (Exception e) {
            synchronized (task) {
                task.setStatus(DagTask.Status.FAILED);
                task.setFinalReply("DAG 任务执行失败，请稍后重试。已完成节点不会丢失。");
                store.save(task);
            }
            log.warn("[DAG] async execution failed dagId={}: {}", task.dagId(), e.getMessage(), e);
            completeAsync(task, DagRunOutcome.completed(task.finalReply()));
        }
    }

    private void completeAsync(DagTask task, DagRunOutcome outcome) {
        if (outcome == null) return;
        if (outcome.status() == DagRunOutcome.Status.PAUSED) {
            notifyProgress(progressCallbacks.get(task.dagId()), outcome.messageToUser());
            return;
        }
        Consumer<DagRunOutcome> callback = completionCallbacks.get(task.dagId());
        if (callback != null) {
            try {
                callback.accept(outcome);
            } catch (Exception e) {
                log.warn("[DAG] completion delivery failed dagId={}: {}", task.dagId(), e.getMessage());
            }
        }
        if (!task.isActive()) releaseTerminalTask(task);
    }

    private void rememberCallbacks(String dagId, UserRequest request, Consumer<String> progress,
                                   Consumer<DagRunOutcome> completion) {
        latestRequests.put(dagId, request);
        if (progress != null) progressCallbacks.put(dagId, progress);
        if (completion != null) completionCallbacks.put(dagId, completion);
    }

    private Optional<DagTask> focusedTask() {
        Optional<DagTask> cached = activeTasks.values().stream()
                .filter(DagTask::isActive).filter(DagTask::focused)
                .max(Comparator.comparingLong(DagTask::updatedAt));
        if (cached.isPresent()) return cached;
        return store.findFocused().map(task -> {
            DagTask existing = activeTasks.putIfAbsent(task.dagId(), task);
            return existing != null ? existing : task;
        });
    }

    private Optional<DagTask> resolveTask(String input) {
        Matcher matcher = DAG_ID_PATTERN.matcher(input != null ? input : "");
        if (matcher.find()) {
            String dagId = matcher.group().toUpperCase(Locale.ROOT);
            DagTask cached = activeTasks.get(dagId);
            if (cached != null && cached.isActive()) return Optional.of(cached);
            return store.findById(dagId).filter(DagTask::isActive).map(task -> {
                activeTasks.put(task.dagId(), task);
                return task;
            });
        }
        return focusedTask();
    }

    private void focus(DagTask task) {
        activeTasks.values().forEach(active -> active.setFocused(false));
        task.setFocused(true);
        activeTasks.put(task.dagId(), task);
        store.save(task);
        store.setFocused(task.dagId());
    }

    private void releaseTerminalTask(DagTask task) {
        activeTasks.remove(task.dagId());
        latestRequests.remove(task.dagId());
        progressCallbacks.remove(task.dagId());
        completionCallbacks.remove(task.dagId());
        if (!task.focused()) return;
        store.findActive().stream()
                .filter(candidate -> !candidate.dagId().equals(task.dagId()))
                .findFirst()
                .ifPresent(this::focus);
    }

    private static String stripDagId(String input) {
        return input == null ? "" : DAG_ID_PATTERN.matcher(input).replaceAll("").trim();
    }

    private static boolean isPauseCommand(String input) {
        String normalized = normalizeCommand(input);
        return normalized.equals("暂停") || normalized.equals("暂停任务") || normalized.equals("pause");
    }

    private static boolean isResumeCommand(String input) {
        String normalized = normalizeCommand(input);
        return normalized.equals("继续") || normalized.equals("继续任务") || normalized.equals("resume");
    }

    private static boolean isCancelCommand(String input) {
        String normalized = normalizeCommand(input);
        return normalized.equals("取消任务") || normalized.equals("取消dag") || normalized.equals("cancel");
    }

    private static boolean isFocusCommand(String input) {
        String normalized = normalizeCommand(input);
        return normalized.equals("切换任务") || normalized.equals("设为当前任务") || normalized.equals("focus");
    }

    private static boolean isListCommand(String input) {
        String normalized = normalizeCommand(input);
        return normalized.equals("任务列表") || normalized.equals("dag列表")
                || normalized.equals("listtasks");
    }

    public static boolean isDagControlMessage(String input) {
        String withoutId = stripDagId(input);
        return DAG_ID_PATTERN.matcher(input != null ? input : "").find()
                || isListCommand(input) || isPauseCommand(withoutId)
                || isResumeCommand(withoutId) || isCancelCommand(withoutId)
                || isFocusCommand(withoutId);
    }

    private String formatTaskList() {
        List<DagTask> tasks = activeDags();
        if (tasks.isEmpty()) return "当前没有进行中的 DAG 任务。";
        StringBuilder message = new StringBuilder("进行中的 DAG 任务：");
        tasks.stream().sorted(Comparator.comparingLong(DagTask::updatedAt).reversed())
                .forEach(task -> message.append("\n")
                        .append(task.focused() ? "* " : "- ")
                        .append(task.dagId()).append(" [").append(task.status()).append("] ")
                        .append(truncate(nonBlank(task.originalText(), "未命名任务"), 48)));
        return message.append("\n使用“DAG_ID 切换任务”切换当前任务。").toString();
    }

    private static String normalizeCommand(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /** Empty means DAG planning or execution is unavailable; callers must return a retryable fallback. */
    public Optional<DagRunOutcome> execute(UserRequest request, Consumer<String> progressCallback) {
        try {
            Optional<DagTask> existing = focusedTask();
            DagTask workflow;
            String latestUserInput = null;
            if (existing.isPresent()) {
                workflow = existing.get();
                latestUserInput = request.text();
                log.info("[DAG] resuming dagId={} revision={} status={}",
                        workflow.dagId(), workflow.revision(), workflow.status());
                logGraph(workflow, "resume");
                DagNode pendingRollback = pendingRollbackNode(workflow);
                if (pendingRollback != null && !isRollbackConfirmation(latestUserInput)) {
                    if (isRollbackCancellation(latestUserInput)) {
                        workflow.setStatus(successCount(workflow) > 0
                                ? DagTask.Status.PARTIAL_SUCCEEDED : DagTask.Status.CANCELLED);
                        workflow.setFinalReply("已取消回退，保留当前已完成结果。\n\n"
                                + aggregateReply(workflow, true));
                        store.save(workflow);
                        log.info("[DAG] side-effect rollback cancelled dagId={} node={}",
                                workflow.dagId(), pendingRollback.id());
                        return Optional.of(completedOutcome(workflow));
                    }
                    workflow.setStatus(DagTask.Status.WAITING_USER);
                    store.save(workflow);
                    log.info("[DAG] rollback still waiting for explicit confirmation dagId={} node={}",
                            workflow.dagId(), pendingRollback.id());
                    return Optional.of(waitingOutcome(workflow));
                }
                if (pendingRollback != null) {
                    log.info("[DAG] rollback confirmed dagId={} node={}",
                            workflow.dagId(), pendingRollback.id());
                    resetRollbackBranch(workflow, pendingRollback);
                } else {
                    resumeWaitingTask(workflow);
                }
                store.save(workflow);
                notifyProgress(progressCallback, "正在从上次中断的节点继续处理...");
            } else {
                DagTask task = new DagTask(newDagId(), request.userId(), request.text());
                PlanResult planned = planTask(task, request);
                if (planned.directOutcome() != null) {
                    if (task.isActive()) focus(task);
                    log.info("[DAG] planner returned direct outcome status={}",
                            planned.directOutcome().status());
                    return Optional.of(planned.directOutcome());
                }
                if (planned.task() == null) {
                    log.warn("[DAG] no valid graph after {} repair attempt(s); DAG execution unavailable",
                            planRepairAttempts);
                    return Optional.empty();
                }
                workflow = planned.task();
                focus(workflow);
            }
            return Optional.of(run(workflow, request, latestUserInput, progressCallback));
        } catch (Exception e) {
            log.warn("DAG orchestration failed; returning unavailable outcome: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Executes a new isolated DAG synchronously without resuming or replacing the focused DAG. */
    public Optional<DagRunOutcome> executeNew(UserRequest request, Consumer<String> progressCallback) {
        try {
            DagTask task = new DagTask(newDagId(), request.userId(), request.text());
            PlanResult planned = planTask(task, request);
            if (planned.directOutcome() != null) return Optional.of(planned.directOutcome());
            if (planned.task() == null) return Optional.empty();
            return Optional.of(run(task, request, null, progressCallback));
        } catch (Exception e) {
            log.warn("new DAG execution failed; returning unavailable outcome: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private PlanResult planTask(DagTask task, UserRequest request) {
        List<String> validationErrors = List.of();
        for (int attempt = 0; attempt <= planRepairAttempts; attempt++) {
            DagPlanDraft draft = planner.planDag(request, validationErrors);
            if (draft == null || draft.status() == DagPlanDraft.Status.UNAVAILABLE) {
                String reason = draft != null && draft.reasoning() != null
                        ? draft.reasoning() : "planner returned no response";
                log.warn("[DAG] planner unavailable attempt={}/{} reason={}; "
                                + "skipping graph repair and returning DAG unavailable",
                        attempt + 1, planRepairAttempts + 1, safeLog(reason));
                task.setStatus(DagTask.Status.FAILED);
                task.setFinalReply("复杂任务规划暂时不可用，请稍后重试。");
                store.save(task);
                logGraph(task, "planning-failed", "PLANNER_UNAVAILABLE");
                return PlanResult.fallback();
            }
            if (draft.status() == DagPlanDraft.Status.INVALID) {
                validationErrors = List.of(draft.reasoning() != null
                        ? draft.reasoning() : "planner returned no usable draft");
                log.warn("[DAG] planner draft invalid attempt={}/{} errors={}",
                        attempt + 1, planRepairAttempts + 1, validationErrors);
                continue;
            }
            if (draft.status() == DagPlanDraft.Status.COMPLETED) {
                String reply = nonBlank(draft.finalReply(), "处理完成。");
                task.setStatus(DagTask.Status.SUCCEEDED);
                task.setFinalReply(reply);
                store.save(task);
                logGraph(task, "planning-direct-completed", "NO_EXECUTION_GRAPH_REQUIRED");
                return PlanResult.direct(DagRunOutcome.completed(reply));
            }
            if (draft.status() == DagPlanDraft.Status.ASK_USER) {
                String question = nonBlank(draft.question(), "请补充完成任务所需的信息。");
                task.setStatus(DagTask.Status.WAITING_USER);
                task.setWaitMessage(question);
                store.save(task);
                logGraph(task, "planning-waiting-user", "PLANNER_REQUIRES_USER_INPUT");
                return PlanResult.direct(DagRunOutcome.waiting(question, List.of()));
            }

            DagCompiler.CompilationResult compilation = compiler.compile(task, draft.nodes(), task.revision());
            if (compilation.valid()) {
                task.addNodes(compilation.nodes());
                task.setStatus(task.status() == DagTask.Status.PAUSE_REQUESTED
                        ? DagTask.Status.PAUSE_REQUESTED : DagTask.Status.RUNNING);
                store.save(task);
                log.info("[DAG] graph compiled dagId={} revision={} nodes={}",
                        task.dagId(), task.revision(), task.nodes().size());
                logGraph(task, "generated");
                return PlanResult.task(task);
            }
            validationErrors = compilation.errors();
            log.warn("DAG draft rejected, repair attempt {}/{}: {}",
                    attempt + 1, planRepairAttempts + 1, validationErrors);
        }
        task.setStatus(DagTask.Status.FAILED);
        store.save(task);
        logGraph(task, "planning-failed", "INVALID_PLAN");
        return PlanResult.fallback();
    }

    private DagRunOutcome run(DagTask workflow, UserRequest request, String latestUserInput,
                               Consumer<String> progressCallback) {
        long deadline = System.currentTimeMillis() + maxExecutionSeconds * 1_000L;
        int waves = 0;
        int replans = 0;

        while (waves < maxWaves && System.currentTimeMillis() < deadline) {
            DagRunOutcome stopped = stopAtCheckpoint(workflow);
            if (stopped != null) return stopped;
            if (workflow.status() == DagTask.Status.WAITING_USER || workflow.hasWaitingNode()) {
                workflow.setStatus(DagTask.Status.WAITING_USER);
                store.save(workflow);
                return waitingOutcome(workflow);
            }

            String beforeWaveInput = workflow.consumePendingUserInputs();
            if (!beforeWaveInput.isBlank()) {
                latestUserInput = beforeWaveInput;
                store.save(workflow);
                if (reflectionEnabled && replans < maxReplans) {
                    ReflectionApplyResult applied = reflectAndApply(
                            workflow, request, latestUserInput, List.of());
                    replans++;
                    if (applied == ReflectionApplyResult.WAITING) return waitingOutcome(workflow);
                    if (applied == ReflectionApplyResult.COMPLETED) return completedOutcome(workflow);
                    if (applied == ReflectionApplyResult.UNCHANGED) {
                        workflow.setStatus(DagTask.Status.WAITING_USER);
                        workflow.setWaitMessage(
                                "无法确定新要求影响哪个 DAG 节点，原进度已保留。请明确要修改的步骤。");
                        store.save(workflow);
                        return waitingOutcome(workflow);
                    }
                }
            }

            List<DagNode> ready = workflow.readyNodes(System.currentTimeMillis());
            if (ready.isEmpty()) {
                long retryAt = nextRetryAt(workflow);
                if (retryAt > System.currentTimeMillis() && retryAt < deadline) {
                    sleepQuietly(Math.min(3_000L, retryAt - System.currentTimeMillis()));
                    continue;
                }
                if (workflow.allSuccessful()) {
                    synchronized (workflow) {
                        if (!workflow.peekPendingUserInputs().isBlank()) continue;
                        workflow.setStatus(DagTask.Status.SUCCEEDED);
                        workflow.setFinalReply(nonBlank(
                                workflow.finalReply(), aggregateReply(workflow, false)));
                        store.save(workflow);
                    }
                    log.info("[DAG] task completed dagId={} waves={} replans={} successfulNodes={}",
                            workflow.dagId(), waves, replans, successCount(workflow));
                    return completedOutcome(workflow);
                }
                if (workflow.hasFailedNode()) {
                    if (reflectionEnabled && replans < maxReplans) {
                        ReflectionApplyResult applied = reflectAndApply(
                                workflow, request, latestUserInput, List.of());
                        replans++;
                        if (applied == ReflectionApplyResult.CHANGED) continue;
                        if (applied == ReflectionApplyResult.WAITING) return waitingOutcome(workflow);
                        if (applied == ReflectionApplyResult.COMPLETED) return completedOutcome(workflow);
                    }
                    return terminalFailure(workflow);
                }
                return terminalFailure(workflow);
            }

            waves++;
            log.info("[DAG] dispatch wave={} dagId={} parallel={} nodes={}",
                    waves, workflow.dagId(), ready.size() > 1,
                    ready.stream().map(DagNode::id).toList());
            notifyWaveStart(progressCallback, waves, ready);
            executeWave(workflow, request, latestUserInput, ready, progressCallback, deadline);
            // A user modification is attached to the first rerun wave only; future reflections
            // inspect fresh node results unless another input arrives.
            latestUserInput = null;
            store.save(workflow);

            stopped = stopAtCheckpoint(workflow);
            if (stopped != null) return stopped;

            if (workflow.hasWaitingNode()) {
                workflow.setStatus(DagTask.Status.WAITING_USER);
                store.save(workflow);
                return waitingOutcome(workflow);
            }

            String newInput = workflow.consumePendingUserInputs();
            if (!newInput.isBlank()) latestUserInput = newInput;
            if (reflectionEnabled && replans < maxReplans) {
                ReflectionApplyResult applied = reflectAndApply(
                        workflow, request, latestUserInput, List.of());
                replans++;
                if (applied == ReflectionApplyResult.WAITING) return waitingOutcome(workflow);
                if (applied == ReflectionApplyResult.COMPLETED) return completedOutcome(workflow);
                if (!newInput.isBlank() && applied == ReflectionApplyResult.UNCHANGED) {
                    workflow.setStatus(DagTask.Status.WAITING_USER);
                    workflow.setWaitMessage("无法确定新要求影响哪个 DAG 节点，原进度已保留。请明确要修改的步骤。");
                    store.save(workflow);
                    return waitingOutcome(workflow);
                }
            }
        }

        finalizeUnfinishedNodes(workflow, "DAG execution deadline or wave limit reached");
        workflow.setStatus(successCount(workflow) > 0
                ? DagTask.Status.PARTIAL_SUCCEEDED : DagTask.Status.FAILED);
        workflow.setFinalReply(aggregateReply(workflow, true));
        store.save(workflow);
        log.warn("[DAG] safety fallback dagId={} waves={}/{} replans={}/{} status={}",
                workflow.dagId(), waves, maxWaves, replans, maxReplans, workflow.status());
        return completedOutcome(workflow);
    }

    private static void finalizeUnfinishedNodes(DagTask workflow, String reason) {
        for (DagNode node : workflow.nodes()) {
            switch (node.status()) {
                case RUNNING, READY, RETRY_WAIT -> {
                    if (node.result() == null) {
                        node.setResult(AgentResult.failed(
                                node.id(), reason, AgentResult.ErrorKind.TIMEOUT));
                    }
                    node.setStatus(DagNode.Status.FAILED);
                }
                case PENDING, INVALIDATED -> {
                    node.setResult(AgentResult.failed(
                            node.id(), reason, AgentResult.ErrorKind.TIMEOUT));
                    node.setStatus(DagNode.Status.BLOCKED);
                }
                default -> {
                    // Terminal and user-waiting states already carry their own resolution.
                }
            }
        }
    }

    private DagRunOutcome stopAtCheckpoint(DagTask task) {
        synchronized (task) {
            if (task.status() == DagTask.Status.PAUSE_REQUESTED) {
                task.setStatus(DagTask.Status.PAUSED);
                store.save(task);
                logGraph(task, "paused");
                return DagRunOutcome.paused("DAG 任务 " + task.dagId() + " 已暂停，已完成节点状态已保存。");
            }
            if (task.status() == DagTask.Status.PAUSED) {
                return DagRunOutcome.paused("DAG 任务 " + task.dagId() + " 当前处于暂停状态。");
            }
            if (task.status() == DagTask.Status.CANCELLED) {
                return completedOutcome(task);
            }
            return null;
        }
    }

    private void executeWave(DagTask workflow, UserRequest request, String latestUserInput,
                             List<DagNode> ready, Consumer<String> progressCallback,
                             long workflowDeadline) {
        List<PendingNodeExecution> pendingExecutions = new ArrayList<>();
        for (DagNode node : ready) {
            AgentTask hydrated = hydrateTask(workflow, node, request, latestUserInput);
            String fingerprint = inputFingerprint(workflow, node, hydrated);
            node.setStatus(DagNode.Status.RUNNING);
            int attempt = node.beginAttempt(workflow.revision(), fingerprint);
            long startedAt = System.currentTimeMillis();
            store.save(workflow);
            log.info("[DAG] node start dagId={} node={} key={} agent={} attempt={}/{} depends={} inputHash={}",
                    workflow.dagId(), node.id(), safeLog(node.key()), node.agentType(),
                    attempt, node.maxAttempts(), node.dependsOn(), shortHash(fingerprint));
            Future<NodeExecution> future = executor.submit(() -> new NodeExecution(
                    node, attempt, startedAt, workflow.inputRevision(),
                    executeNode(node, hydrated, progressCallback)));
            pendingExecutions.add(new PendingNodeExecution(
                    node, attempt, startedAt, workflow.inputRevision(), future));
        }

        for (PendingNodeExecution pending : pendingExecutions) {
            NodeExecution execution;
            try {
                long nodeDeadline = Math.min(workflowDeadline,
                        pending.startedAt() + nodeTimeoutSeconds * 1_000L);
                long remainingMs = Math.max(1L, nodeDeadline - System.currentTimeMillis());
                execution = pending.future().get(remainingMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.future().cancel(true);
                String error = "node timed out after " + nodeTimeoutSeconds + " seconds";
                log.warn("[DAG] node timeout dagId={} node={} attempt={}/{} timeoutSeconds={}",
                        workflow.dagId(), pending.node().id(), pending.attempt(),
                        pending.node().maxAttempts(), nodeTimeoutSeconds);
                execution = failedExecution(pending, error, AgentResult.ErrorKind.TIMEOUT);
            } catch (InterruptedException e) {
                pendingExecutions.forEach(item -> item.future().cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DAG wave interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String error = cause != null && cause.getMessage() != null
                        ? cause.getMessage() : "agent execution future failed";
                log.warn("[DAG] node future failed dagId={} node={} attempt={} error={}",
                        workflow.dagId(), pending.node().id(), pending.attempt(), safeLog(error));
                execution = failedExecution(pending, error, AgentResult.classify(error));
            }
            applyNodeResult(workflow, execution, progressCallback);
        }
    }

    private static NodeExecution failedExecution(PendingNodeExecution pending, String error,
                                                  AgentResult.ErrorKind errorKind) {
        return new NodeExecution(
                pending.node(), pending.attempt(), pending.startedAt(), pending.inputRevision(),
                AgentResult.failed(pending.node().id(), error, errorKind));
    }

    private AgentResult executeNode(DagNode node, AgentTask task, Consumer<String> progressCallback) {
        try {
            AgentUnit agent = registry.get(node.agentType());
            SkillTools.setCurrentAgent(node.agentType());
            try {
                return agent.execute(task, progressCallback);
            } finally {
                SkillTools.clearCurrentAgent();
            }
        } catch (Exception e) {
            return AgentResult.failed(node.id(), e.getMessage(), AgentResult.classify(e.getMessage()));
        }
    }

    private void applyNodeResult(DagTask workflow, NodeExecution execution,
                                 Consumer<String> progressCallback) {
        DagNode node = execution.node();
        AgentResult result = execution.result() != null ? execution.result()
                : AgentResult.failed(node.id(), "agent returned no result");
        node.setResult(result);
        long finishedAt = System.currentTimeMillis();
        store.recordAttempt(workflow.dagId(), node, execution.attempt(), execution.startedAt(),
                finishedAt, result);

        if (result.status() == AgentResult.Status.SUCCESS) {
            node.setStatus(DagNode.Status.SUCCEEDED);
            node.markResultRevision(workflow.revision());
            notifyProgress(progressCallback, "已完成：" + node.key());
        } else if (result.isPaused()) {
            node.setStatus(DagNode.Status.WAITING_USER);
            workflow.setWaitMessage(nonBlank(result.messageToUser(), "请补充信息后继续。"));
        } else if (failurePolicy.needsUserInput(result)) {
            node.setStatus(DagNode.Status.WAITING_USER);
            workflow.setWaitMessage(failurePolicy.userMessage(result));
        } else if (failurePolicy.shouldRetry(result, node.attemptCount(), node.maxAttempts())) {
            node.setStatus(DagNode.Status.RETRY_WAIT);
            node.setNextAttemptAt(System.currentTimeMillis()
                    + failurePolicy.retryDelayMillis(node.attemptCount()));
            notifyProgress(progressCallback, "步骤“" + node.key() + "”失败，正在重试（"
                    + node.attemptCount() + "/" + node.maxAttempts() + "）...");
        } else {
            node.setStatus(DagNode.Status.FAILED);
        }
        workflow.touch();
        store.save(workflow);
        log.info("[DAG] node finish dagId={} node={} attempt={} agentResult={} nodeStatus={} "
                        + "errorKind={} durationMs={} inputRevision={}->{} error={}",
                workflow.dagId(), node.id(), execution.attempt(), result.status(), node.status(),
                result.errorKind(), finishedAt - execution.startedAt(), execution.inputRevision(),
                workflow.inputRevision(), safeLog(result.errorMessage()));
    }

    private ReflectionApplyResult reflectAndApply(DagTask workflow, UserRequest request,
                                                  String latestUserInput, List<String> errors) {
        DagReflection reflection = planner.reflectDag(
                request, DagContextMapper.forOrchestrator(workflow, latestUserInput), errors);
        if (reflection == null || reflection.action() == DagReflection.Action.INVALID) {
            log.warn("[DAG] reflection invalid dagId={} revision={}",
                    workflow.dagId(), workflow.revision());
            return ReflectionApplyResult.UNCHANGED;
        }
        log.info("[DAG] reflection dagId={} revision={} action={} target={} newNodes={} reason={}",
                workflow.dagId(), workflow.revision(), reflection.action(),
                safeLog(reflection.targetNode()), reflection.newNodes().size(), safeLog(reflection.reason()));
        return switch (reflection.action()) {
            case CONTINUE -> ReflectionApplyResult.UNCHANGED;
            case COMPLETE, PARTIAL_COMPLETE -> {
                workflow.setStatus(reflection.action() == DagReflection.Action.COMPLETE
                        ? DagTask.Status.SUCCEEDED : DagTask.Status.PARTIAL_SUCCEEDED);
                workflow.setFinalReply(nonBlank(reflection.finalReply(),
                        aggregateReply(workflow, reflection.action() == DagReflection.Action.PARTIAL_COMPLETE)));
                store.save(workflow);
                yield ReflectionApplyResult.COMPLETED;
            }
            case ASK_USER -> {
                workflow.setStatus(DagTask.Status.WAITING_USER);
                workflow.setWaitMessage(nonBlank(reflection.question(), "请补充完成任务所需的信息。"));
                DagNode target = workflow.findByKeyOrId(reflection.targetNode());
                if (target != null) target.setStatus(DagNode.Status.WAITING_USER);
                store.save(workflow);
                yield ReflectionApplyResult.WAITING;
            }
            case RETRY -> retryNode(workflow, reflection.targetNode());
            case ROLLBACK -> rollback(workflow, reflection.targetNode());
            case APPEND -> appendNodesWithRepair(workflow, request, latestUserInput, reflection);
            case INVALID -> ReflectionApplyResult.UNCHANGED;
        };
    }

    private ReflectionApplyResult appendNodesWithRepair(DagTask workflow, UserRequest request,
                                                        String latestUserInput, DagReflection reflection) {
        DagCompiler.CompilationResult compiled = compiler.compile(
                workflow, reflection.newNodes(), workflow.revision() + 1);
        if (!compiled.valid()) {
            DagReflection repaired = planner.reflectDag(
                    request, DagContextMapper.forOrchestrator(workflow, latestUserInput), compiled.errors());
            if (repaired == null || repaired.action() != DagReflection.Action.APPEND) {
                return ReflectionApplyResult.UNCHANGED;
            }
            compiled = compiler.compile(workflow, repaired.newNodes(), workflow.revision() + 1);
        }
        if (!compiled.valid()) {
            log.warn("reflection DAG patch rejected: {}", compiled.errors());
            return ReflectionApplyResult.UNCHANGED;
        }
        workflow.incrementRevision();
        workflow.addNodes(compiled.nodes());
        workflow.setStatus(DagTask.Status.RUNNING);
        store.save(workflow);
        log.info("[DAG] graph patched dagId={} revision={} addedNodes={}",
                workflow.dagId(), workflow.revision(), compiled.nodes().stream().map(DagNode::id).toList());
        logGraph(workflow, "reflection-append");
        return ReflectionApplyResult.CHANGED;
    }

    private ReflectionApplyResult retryNode(DagTask workflow, String targetNode) {
        DagNode node = workflow.findByKeyOrId(targetNode);
        if (node == null || node.status() == DagNode.Status.RUNNING) {
            return ReflectionApplyResult.UNCHANGED;
        }
        node.resetForRetry(true);
        workflow.setStatus(DagTask.Status.RUNNING);
        workflow.incrementRevision();
        store.save(workflow);
        log.info("[DAG] reflection retry scheduled dagId={} node={} revision={}",
                workflow.dagId(), node.id(), workflow.revision());
        return ReflectionApplyResult.CHANGED;
    }

    private ReflectionApplyResult rollback(DagTask workflow, String targetNode) {
        DagNode root = workflow.findByKeyOrId(targetNode);
        if (root == null || root.status() == DagNode.Status.RUNNING) {
            log.warn("[DAG] rollback rejected dagId={} target={}",
                    workflow.dagId(), safeLog(targetNode));
            return ReflectionApplyResult.UNCHANGED;
        }
        List<DagNode> affected = new ArrayList<>();
        affected.add(root);
        workflow.descendantIds(root.id()).stream().map(workflow::node).forEach(affected::add);
        boolean completedSideEffect = affected.stream().anyMatch(node -> node.isSideEffectRisk()
                && node.status() == DagNode.Status.SUCCEEDED);
        if (completedSideEffect) {
            root.setResult(withSignal(root.result(), "dag.pendingRollback", "true"));
            root.setStatus(DagNode.Status.WAITING_USER);
            workflow.setStatus(DagTask.Status.WAITING_USER);
            workflow.setWaitMessage("回退会影响已经执行的网页或出行操作，请确认后再继续。回复“确认回退”或取消任务。");
            store.save(workflow);
            log.warn("[DAG] rollback requires confirmation dagId={} root={} affected={}",
                    workflow.dagId(), root.id(), affected.stream().map(DagNode::id).toList());
            return ReflectionApplyResult.WAITING;
        }
        resetRollbackBranch(workflow, root);
        store.save(workflow);
        log.info("[DAG] rollback applied dagId={} root={} affected={} revision={}",
                workflow.dagId(), root.id(), affected.stream().map(DagNode::id).toList(),
                workflow.revision());
        logGraph(workflow, "reflection-rollback");
        return ReflectionApplyResult.CHANGED;
    }

    private void resetRollbackBranch(DagTask workflow, DagNode root) {
        logInvalidation(workflow, root, "ROLLBACK_ROOT");
        root.resetForRetry(true);
        for (String descendantId : workflow.descendantIds(root.id())) {
            DagNode node = workflow.node(descendantId);
            logInvalidation(workflow, node, "ROLLBACK_DESCENDANT");
            node.resetForRetry(true);
            node.setStatus(DagNode.Status.INVALIDATED);
        }
        workflow.incrementRevision();
        workflow.setStatus(DagTask.Status.RUNNING);
        workflow.setWaitMessage(null);
    }

    private static void logInvalidation(DagTask task, DagNode node, String reason) {
        log.info("[DAG] node invalidated dagId={} node={} reason={} oldStatus={} "
                        + "oldInputHash={} executionRevision={} resultRevision={}",
                task.dagId(), node.id(), reason, node.status(), shortHash(node.inputFingerprint()),
                node.executionRevision(), node.resultRevision());
    }

    private AgentTask hydrateTask(DagTask workflow, DagNode node, UserRequest request,
                                  String latestUserInput) {
        Map<String, Object> parameters = new LinkedHashMap<>(node.parameters());
        parameters.put("userId", request.userId());
        String instruction = node.instruction();

        if (latestUserInput != null && !latestUserInput.isBlank()) {
            parameters.put("resumeUserMessage", latestUserInput);
        }
        List<String> images = new ArrayList<>();
        if ("CHAT".equals(node.agentType())) {
            images.addAll(request.imageBase64Urls());
            images.addAll(request.rememberedImageBase64Urls());
            images.addAll(DagContextMapper.dependencyImageDataUrls(workflow, node));
            images = images.stream().distinct().toList();
            if (!images.isEmpty()) parameters.put("imageUrls", images);
        }
        if ("SPEECH_GEN".equals(node.agentType())) {
            String lastChat = lastSuccessfulDependencyText(workflow, node, "CHAT");
            parameters.put("text", lastChat != null && !lastChat.isBlank() ? lastChat : node.instruction());
        }
        if ("BROWSER".equals(node.agentType()) && instruction.contains("{{LAST_CHAT_TEXT}}")) {
            String lastChat = lastSuccessfulDependencyText(workflow, node, "CHAT");
            if (lastChat != null && !lastChat.isBlank()) {
                if (lastChat.length() <= MAX_INLINE_TEXT_CHARS) {
                    instruction = instruction.replace("{{LAST_CHAT_TEXT}}", lastChat);
                } else {
                    parameters.put("textPayload", lastChat);
                    instruction = instruction.replace("{{LAST_CHAT_TEXT}}", "[TEXT_PAYLOAD:LAST_CHAT_TEXT]");
                }
            }
        }
        AgentExecutionContext executionContext = AgentExecutionContext.isolated(
                request.userId(),
                DagContextMapper.forAgent(workflow, node, latestUserInput),
                images,
                request.rememberedImageSummary());
        return AgentTask.restore(node.id(), node.agentType(), instruction, node.contextNote(), parameters)
                .withExecutionContext(executionContext);
    }

    private static String lastSuccessfulDependencyText(DagTask workflow, DagNode currentNode,
                                                       String agentType) {
        java.util.Set<String> dependencies = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(currentNode.dependsOn());
        while (!queue.isEmpty()) {
            String dependencyId = queue.removeFirst();
            if (!dependencies.add(dependencyId)) {
                continue;
            }
            DagNode dependency = workflow.node(dependencyId);
            if (dependency != null) {
                queue.addAll(dependency.dependsOn());
            }
        }
        String latest = null;
        for (DagNode node : workflow.nodes()) {
            if (dependencies.contains(node.id())
                    && agentType.equals(node.agentType())
                    && node.status() == DagNode.Status.SUCCEEDED
                    && node.result() != null
                    && node.result().rawOutput() != null
                    && !node.result().rawOutput().isBlank()) {
                latest = node.result().rawOutput();
            }
        }
        return latest;
    }

    private void resumeWaitingTask(DagTask workflow) {
        for (DagNode node : workflow.nodes()) {
            if (node.status() == DagNode.Status.WAITING_USER) {
                node.resetForRetry(false);
            }
        }
        workflow.setStatus(DagTask.Status.RUNNING);
        workflow.setWaitMessage(null);
    }

    private static DagNode pendingRollbackNode(DagTask workflow) {
        return workflow.nodes().stream()
                .filter(node -> node.status() == DagNode.Status.WAITING_USER && node.result() != null)
                .filter(node -> "true".equals(node.result().signals().get("dag.pendingRollback")))
                .findFirst().orElse(null);
    }

    private static AgentResult withSignal(AgentResult result, String key, String value) {
        if (result == null) return null;
        Map<String, String> signals = new LinkedHashMap<>(result.signals());
        signals.put(key, value);
        return new AgentResult(result.taskId(), result.status(), result.output(), result.rawOutput(),
                result.errorMessage(), result.errorKind(), result.messageToUser(), result.resumeState(),
                result.pausedImages(), signals);
    }

    private static AgentResult withoutSignal(AgentResult result, String key) {
        if (result == null) return null;
        Map<String, String> signals = new LinkedHashMap<>(result.signals());
        signals.remove(key);
        return new AgentResult(result.taskId(), result.status(), result.output(), result.rawOutput(),
                result.errorMessage(), result.errorKind(), result.messageToUser(), result.resumeState(),
                result.pausedImages(), signals);
    }

    private static UserRequest planningRequest(DagTask task, UserRequest latestRequest) {
        String additions = task.peekPendingUserInputs();
        String latestText = latestRequest.text();
        if (additions.isBlank() && latestText != null
                && !latestText.isBlank() && !latestText.equals(task.originalText())) {
            additions = latestText;
        }
        String planningText = task.originalText();
        if (!additions.isBlank()) {
            planningText = planningText + "\n\n用户后续补充或修改：\n" + additions;
        }
        return new UserRequest(latestRequest.userId(), planningText,
                latestRequest.imageBase64Urls(), latestRequest.history(),
                latestRequest.rememberedImageBase64Urls(), latestRequest.rememberedImageSummary(),
                ContextStage.PLAN, ContextTaskState.empty());
    }

    private static boolean isRollbackConfirmation(String input) {
        if (input == null) return false;
        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("确认回退") || normalized.contains("confirm rollback");
    }

    private static boolean isRollbackCancellation(String input) {
        if (input == null) return false;
        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("取消回退") || normalized.contains("不回退")
                || normalized.contains("cancel rollback");
    }

    private DagRunOutcome terminalFailure(DagTask workflow) {
        workflow.setStatus(successCount(workflow) > 0
                ? DagTask.Status.PARTIAL_SUCCEEDED : DagTask.Status.FAILED);
        workflow.setFinalReply(aggregateReply(workflow, true));
        store.save(workflow);
        log.warn("[DAG] terminal fallback dagId={} status={} successfulNodes={} failedNodes={}",
                workflow.dagId(), workflow.status(), successCount(workflow),
                workflow.nodes().stream().filter(node -> node.status() == DagNode.Status.FAILED
                        || node.status() == DagNode.Status.BLOCKED).map(DagNode::id).toList());
        return completedOutcome(workflow);
    }

    private DagRunOutcome waitingOutcome(DagTask workflow) {
        List<ModelReply.ImagePayload> images = workflow.nodes().stream()
                .map(DagNode::result).filter(java.util.Objects::nonNull)
                .flatMap(result -> result.pausedImages().stream()).toList();
        log.info("[DAG] waiting for user dagId={} nodes={} message={}",
                workflow.dagId(), workflow.nodes().stream()
                        .filter(node -> node.status() == DagNode.Status.WAITING_USER)
                        .map(DagNode::id).toList(), safeLog(workflow.waitMessage()));
        return DagRunOutcome.waiting(nonBlank(workflow.waitMessage(), "请补充信息后继续。"), images);
    }

    private DagRunOutcome completedOutcome(DagTask workflow) {
        List<DagRunOutcome.NodeOutput> outputs = workflow.nodes().stream()
                .filter(node -> node.result() != null)
                .map(node -> new DagRunOutcome.NodeOutput(
                        node.id(), node.agentType(), node.result()))
                .toList();
        return DagRunOutcome.completed(
                nonBlank(workflow.finalReply(), aggregateReply(workflow, false)), outputs);
    }

    private static String aggregateReply(DagTask workflow, boolean partial) {
        List<String> outputs = workflow.nodes().stream()
                .filter(node -> node.status() == DagNode.Status.SUCCEEDED && node.result() != null)
                .map(node -> node.result().rawOutput())
                .filter(output -> output != null && !output.isBlank())
                .toList();
        if (!partial) {
            return outputs.isEmpty() ? "任务已完成。" : "任务已完成。\n\n" + String.join("\n\n", outputs);
        }
        List<String> failures = workflow.nodes().stream()
                .filter(node -> node.status() == DagNode.Status.FAILED
                        || node.status() == DagNode.Status.BLOCKED)
                .map(node -> "- " + node.key() + "：" + safeFailure(node))
                .toList();
        String prefix = outputs.isEmpty() ? "任务未能完成，执行进度已保存。"
                : "任务已部分完成，成功节点的结果和执行进度已保存。";
        StringBuilder reply = new StringBuilder(prefix);
        if (!outputs.isEmpty()) {
            reply.append("\n\n已完成结果：\n").append(String.join("\n\n", outputs));
        }
        if (!failures.isEmpty()) {
            reply.append("\n\n失败步骤：\n").append(String.join("\n", failures));
        }
        reply.append("\n\n可以重新提交失败步骤，已完成节点不会丢失。");
        return reply.toString();
    }

    private static String safeFailure(DagNode node) {
        String error = node.result() != null ? node.result().errorMessage() : null;
        return error == null || error.isBlank() ? "未知错误" : safeLog(error);
    }

    private static long successCount(DagTask workflow) {
        return workflow.nodes().stream().filter(node -> node.status() == DagNode.Status.SUCCEEDED).count();
    }

    private static long nextRetryAt(DagTask workflow) {
        return workflow.nodes().stream()
                .filter(node -> node.status() == DagNode.Status.RETRY_WAIT)
                .mapToLong(DagNode::nextAttemptAt).min().orElse(0L);
    }

    private static String newDagId() {
        return "D-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private static String safeLog(String value) {
        if (value == null) return "";
        return truncate(value.replace('\n', ' ').replace('\r', ' '), 240);
    }

    private static void logGraph(DagTask workflow, String event) {
        logGraph(workflow, event, null);
    }

    private static void logGraph(DagTask workflow, String event, String reason) {
        log.info("[DAG-GRAPH] BEGIN event={} dagId={} revision={} status={} reason={}",
                event, workflow.dagId(), workflow.revision(), workflow.status(),
                reason != null ? reason : "-");
        DagConsoleRenderer.render(workflow).lines()
                .forEach(line -> log.info("[DAG-GRAPH] {}", line));
        log.info("[DAG-GRAPH] END dagId={}", workflow.dagId());
    }

    private static String inputFingerprint(DagTask task, DagNode node, AgentTask hydrated) {
        StringBuilder source = new StringBuilder()
                .append(hydrated.instruction()).append('|')
                .append(node.agentType()).append('|');
        hydrated.parameters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.append(entry.getKey()).append('=')
                        .append(String.valueOf(entry.getValue())).append(';'));
        node.dependsOn().stream().sorted().forEach(id -> {
            DagNode dependency = task.node(id);
            source.append("dep:").append(id).append('@')
                    .append(dependency != null ? dependency.resultRevision() : 0).append(';');
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(source.toString().hashCode());
        }
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(12, hash.length()));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void notifyWaveStart(Consumer<String> callback, int wave, List<DagNode> nodes) {
        if (callback == null) return;
        String tasks = nodes.stream().map(DagNode::key).reduce((a, b) -> a + "、" + b).orElse("任务");
        notifyProgress(callback, "第 " + wave + " 轮开始：" + tasks);
    }

    private static void notifyProgress(Consumer<String> callback, String message) {
        if (callback == null) return;
        try {
            callback.accept(message);
        } catch (Exception ignored) {
            // Progress delivery must not fail the workflow.
        }
    }

    private enum ReflectionApplyResult { CHANGED, UNCHANGED, WAITING, COMPLETED }

    private record NodeExecution(DagNode node, int attempt, long startedAt,
                                 int inputRevision, AgentResult result) {}

    private record PendingNodeExecution(DagNode node, int attempt, long startedAt,
                                        int inputRevision, Future<NodeExecution> future) {}

    private record PlanResult(DagTask task, DagRunOutcome directOutcome) {
        static PlanResult task(DagTask task) { return new PlanResult(task, null); }
        static PlanResult direct(DagRunOutcome outcome) { return new PlanResult(null, outcome); }
        static PlanResult fallback() { return new PlanResult(null, null); }
    }

    public record DagSubmission(Status status, String dagId, String message) {
        public enum Status { ACCEPTED, CONTROLLED, NO_ACTIVE_DAG, UNAVAILABLE }

        static DagSubmission accepted(String dagId, String message) {
            return new DagSubmission(Status.ACCEPTED, dagId, message);
        }

        static DagSubmission controlled(String dagId, String message) {
            return new DagSubmission(Status.CONTROLLED, dagId, message);
        }

        static DagSubmission noActive() {
            return new DagSubmission(Status.NO_ACTIVE_DAG, null, null);
        }

        static DagSubmission unavailable(String message) {
            return new DagSubmission(Status.UNAVAILABLE, null, message);
        }
    }
}
