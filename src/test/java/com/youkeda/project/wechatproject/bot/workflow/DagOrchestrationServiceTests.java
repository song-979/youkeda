package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.DagOrchestrationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DagOrchestrationServiceTests {

    private final List<DagOrchestrationService> services = new ArrayList<>();

    @AfterEach
    void shutdownServices() {
        services.forEach(DagOrchestrationService::shutdown);
    }

    @Test
    void executesIndependentRootsInParallelBeforeDependentNode() {
        CountDownLatch rootsStarted = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean();
        List<String> completed = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentUnit agent = agent(task -> {
            if (task.instruction().startsWith("root")) {
                rootsStarted.countDown();
                try {
                    overlapped.set(rootsStarted.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            completed.add(task.instruction());
            return AgentResult.success(task.taskId(), task.instruction(), task.instruction());
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("parallel", List.of(
                node("a", "root-a", List.of()),
                node("b", "root-b", List.of()),
                node("join", "join", List.of("a", "b"))))));

        DagRunOutcome outcome = service(planner, agent, new InMemoryStore(), true)
                .execute(request("user-1", "parallel request"), null).orElseThrow();

        assertThat(outcome.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(overlapped).isTrue();
        assertThat(completed).containsExactlyInAnyOrder("root-a", "root-b", "join");
        assertThat(completed.indexOf("join")).isGreaterThan(completed.indexOf("root-a"));
        assertThat(completed.indexOf("join")).isGreaterThan(completed.indexOf("root-b"));
    }

    @Test
    void retriesOnlyFailedNodeAndContinuesFromCheckpoint() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        AgentUnit agent = agent(task -> {
            int count = calls.computeIfAbsent(task.instruction(), ignored -> new AtomicInteger()).incrementAndGet();
            if (task.instruction().equals("flaky") && count == 1) {
                return AgentResult.failed(task.taskId(), "timeout", AgentResult.ErrorKind.TIMEOUT);
            }
            return AgentResult.success(task.taskId(), "ok", "ok-" + task.instruction());
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("retry", List.of(
                node("flaky", "flaky", List.of()),
                node("stable", "stable", List.of()),
                node("finish", "finish", List.of("flaky", "stable"))))));

        DagRunOutcome outcome = service(planner, agent, new InMemoryStore(), true)
                .execute(request("user-2", "retry request"), null).orElseThrow();

        assertThat(outcome.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(calls.get("flaky")).hasValue(2);
        assertThat(calls.get("stable")).hasValue(1);
        assertThat(calls.get("finish")).hasValue(1);
    }

    @Test
    void timesOutBlockedNodeAndReturnsTerminalFallback() {
        AgentUnit blockedAgent = agent(task -> {
            try {
                Thread.sleep(10_000);
                return AgentResult.success(task.taskId(), "late", "late");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentResult.failed(task.taskId(), "interrupted", AgentResult.ErrorKind.TIMEOUT);
            }
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("blocked", List.of(
                node("blocked", "blocked call", List.of())))));
        DagOrchestrationProperties properties = new DagOrchestrationProperties();
        properties.setReflectionEnabled(false);
        properties.setDagDefaultMaxAttempts(1);
        properties.setDagNodeTimeoutSeconds(1);
        DagOrchestrationService service = new DagOrchestrationService(
                planner, new AgentRegistry(List.of(blockedAgent), null), new InMemoryStore(), properties);
        services.add(service);

        long startedAt = System.nanoTime();
        DagRunOutcome outcome = service.execute(request("blocked-user", "blocked request"), null)
                .orElseThrow();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs).isBetween(900L, 2_500L);
        assertThat(outcome.finalReply()).contains("任务未能完成", "blocked", "timed out");
        assertThat(outcome.nodeOutputs()).singleElement().satisfies(output -> {
            assertThat(output.result().status()).isEqualTo(AgentResult.Status.FAILED);
            assertThat(output.result().errorKind()).isEqualTo(AgentResult.ErrorKind.TIMEOUT);
        });
    }

    @Test
    void repairsInvalidPlanOnceAndFallsBackWhenRepairIsStillInvalid() {
        DagPlanDraft invalid = DagPlanDraft.dag("bad", List.of(
                new DagNodeDraft("bad", "UNKNOWN", "bad", null, List.of(), Map.of())));
        DagPlanDraft valid = DagPlanDraft.dag("fixed", List.of(node("ok", "ok", List.of())));
        ScriptedPlanner repairable = new ScriptedPlanner(List.of(invalid, valid));
        AgentUnit agent = agent(task -> AgentResult.success(task.taskId(), "ok", "ok"));

        Optional<DagRunOutcome> repaired = service(repairable, agent, new InMemoryStore(), false)
                .execute(request("user-3", "repair"), null);

        assertThat(repaired).isPresent();
        assertThat(repairable.planCalls).hasValue(2);
        assertThat(repairable.lastValidationErrors).anyMatch(error -> error.contains("unknown agent"));

        ScriptedPlanner rejected = new ScriptedPlanner(List.of(invalid, invalid));
        Optional<DagRunOutcome> fallback = service(rejected, agent, new InMemoryStore(), false)
                .execute(request("user-4", "fallback"), null);

        assertThat(fallback).isEmpty();
        assertThat(rejected.planCalls).hasValue(2);
    }

    @Test
    void providerFailureFallsBackWithoutWastingGraphRepairAttempt() {
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                DagPlanDraft.unavailable("AI response contained no usable assistant content"),
                DagPlanDraft.dag("should not be called", List.of(node("late", "late", List.of())))));
        AgentUnit agent = agent(task -> AgentResult.success(task.taskId(), "ok", "ok"));

        Optional<DagRunOutcome> outcome = service(planner, agent, new InMemoryStore(), false)
                .execute(request("user-provider-down", "complex request"), null);

        assertThat(outcome).isEmpty();
        assertThat(planner.planCalls).hasValue(1);
    }

    @Test
    void reflectionCanAppendNodesAndRollbackOnlyAffectedBranch() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        AgentUnit agent = agent(task -> {
            calls.computeIfAbsent(task.instruction(), ignored -> new AtomicInteger()).incrementAndGet();
            return AgentResult.success(task.taskId(), "ok", "ok-" + task.instruction());
        });
        ScriptedPlanner appendPlanner = new ScriptedPlanner(List.of(DagPlanDraft.dag("base", List.of(
                node("base", "base", List.of())))));
        appendPlanner.reflections.add(new DagReflection(DagReflection.Action.APPEND, null, "add child",
                null, null, List.of(node("child", "child", List.of("base")))));

        DagRunOutcome appended = service(appendPlanner, agent, new InMemoryStore(), true)
                .execute(request("user-5", "append"), null).orElseThrow();

        assertThat(appended.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(calls.get("base")).hasValue(1);
        assertThat(calls.get("child")).hasValue(1);

        calls.clear();
        ScriptedPlanner rollbackPlanner = new ScriptedPlanner(List.of(DagPlanDraft.dag("rollback", List.of(
                node("base", "base", List.of()),
                node("unrelated", "unrelated", List.of()),
                node("child", "child", List.of("base"))))));
        rollbackPlanner.reflections.add(new DagReflection(DagReflection.Action.ROLLBACK, "base", "redo base",
                null, null, List.of()));

        DagRunOutcome rolledBack = service(rollbackPlanner, agent, new InMemoryStore(), true)
                .execute(request("user-6", "rollback"), null).orElseThrow();

        assertThat(rolledBack.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(calls.get("base")).hasValue(2);
        assertThat(calls.get("child")).hasValue(1);
        assertThat(calls.get("unrelated")).hasValue(1);
    }

    @Test
    void timePausedNodeSleepsAndHeartbeatCanResumeIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentUnit agent = agent(task -> {
            if (calls.incrementAndGet() == 1) {
                return AgentResult.paused(task.taskId(), null, Map.of(
                        "pauseType", "TIME",
                        "wakeAt", "2026-08-05T15:00:00+08:00"));
            }
            return AgentResult.success(task.taskId(), "done", "done");
        });
        InMemoryStore store = new InMemoryStore();
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("sleep", List.of(
                node("wait", "wait", List.of())))));
        DagOrchestrationService service = service(planner, agent, store, false);

        DagRunOutcome sleeping = service.execute(request("user-sleep", "wait for data"), null)
                .orElseThrow();
        DagTask task = store.findActive().getFirst();
        assertThat(sleeping.status()).isEqualTo(DagRunOutcome.Status.PAUSED);
        assertThat(task.status()).isEqualTo(DagTask.Status.SLEEPING);
        assertThat(task.nodes()).singleElement()
                .extracting(DagNode::status)
                .isEqualTo(DagNode.Status.SLEEPING);
        CountDownLatch completed = new CountDownLatch(1);

        DagOrchestrationService.DagSubmission resumed = service.resumeSleeping(
                task.dagId(), request("user-sleep", "wait for data"),
                outcome -> completed.countDown());

        assertThat(resumed.status()).isEqualTo(DagOrchestrationService.DagSubmission.Status.ACCEPTED);
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(store.findActive()).isEmpty();
    }

    @Test
    void verifiedTransientFailureSleepsOnSharedHeartbeatAndResumesFromCheckpoint() throws Exception {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        AgentUnit agent = agent(task -> {
            int count = calls.computeIfAbsent(task.instruction(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (task.instruction().equals("flaky") && count == 1) {
                return AgentResult.failed(task.taskId(),
                        "503 service unavailable", AgentResult.ErrorKind.UPSTREAM);
            }
            return AgentResult.success(task.taskId(), "ok", "ok-" + task.instruction());
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("deferred", List.of(
                node("flaky", "flaky", List.of()),
                node("stable", "stable", List.of()),
                node("finish", "finish", List.of("flaky", "stable"))))));
        InMemoryStore store = new InMemoryStore();
        DagOrchestrationProperties properties = new DagOrchestrationProperties();
        properties.setReflectionEnabled(false);
        properties.setDagDefaultMaxAttempts(1);
        properties.setDagDeferredRetryFirstMinutes(1);
        List<Instant> scheduledWakes = new ArrayList<>();
        DagOrchestrationService service = new DagOrchestrationService(
                planner, new AgentRegistry(List.of(agent), null), store, properties,
                null, null, (userId, wakeAt, reason) -> {
                    scheduledWakes.add(wakeAt);
                    return true;
                });
        services.add(service);

        DagRunOutcome sleeping = service.execute(
                request("user-deferred", "retry later"), null).orElseThrow();
        DagTask task = store.findActive().getFirst();
        DagNode retryNode = task.nodes().stream()
                .filter(node -> node.key().equals("flaky")).findFirst().orElseThrow();

        assertThat(sleeping.status()).isEqualTo(DagRunOutcome.Status.PAUSED);
        assertThat(task.status()).isEqualTo(DagTask.Status.SLEEPING);
        assertThat(retryNode.status()).isEqualTo(DagNode.Status.RETRY_WAIT);
        assertThat(scheduledWakes).containsExactly(Instant.ofEpochMilli(retryNode.nextAttemptAt()));
        assertThat(service.resumeSleeping(task.dagId(), request("user-deferred", "retry later"), null)
                .status()).isEqualTo(DagOrchestrationService.DagSubmission.Status.UNAVAILABLE);

        retryNode.setNextAttemptAt(System.currentTimeMillis() - 1);
        store.save(task);
        CountDownLatch completed = new CountDownLatch(1);
        DagOrchestrationService.DagSubmission resumed = service.resumeSleeping(
                task.dagId(), request("user-deferred", "retry later"),
                outcome -> completed.countDown());

        assertThat(resumed.status()).isEqualTo(DagOrchestrationService.DagSubmission.Status.ACCEPTED);
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get("flaky")).hasValue(2);
        assertThat(calls.get("stable")).hasValue(1);
        assertThat(calls.get("finish")).hasValue(1);
    }

    @Test
    void unknownFailureTerminatesWithoutSchedulingHeartbeatSleep() {
        AgentUnit agent = agent(task -> AgentResult.failed(
                task.taskId(), "unclassified failure", AgentResult.ErrorKind.UNKNOWN));
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("terminal", List.of(
                node("unknown", "unknown", List.of())))));
        InMemoryStore store = new InMemoryStore();
        DagOrchestrationProperties properties = new DagOrchestrationProperties();
        properties.setReflectionEnabled(false);
        properties.setDagDefaultMaxAttempts(1);
        List<Instant> scheduledWakes = new ArrayList<>();
        DagOrchestrationService service = new DagOrchestrationService(
                planner, new AgentRegistry(List.of(agent), null), store, properties,
                null, null, (userId, wakeAt, reason) -> {
                    scheduledWakes.add(wakeAt);
                    return true;
                });
        services.add(service);

        DagRunOutcome outcome = service.execute(
                request("user-terminal", "do not retry unknown errors"), null).orElseThrow();

        assertThat(outcome.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(scheduledWakes).isEmpty();
        assertThat(store.findActive()).isEmpty();
        assertThat(outcome.finalReply()).contains("失败步骤");
    }

    @Test
    void resumesWaitingNodeWithLatestUserInput() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean receivedResumeInput = new AtomicBoolean();
        AgentUnit agent = agent(task -> {
            if (calls.incrementAndGet() == 1) {
                return AgentResult.paused(task.taskId(), "need city", Map.of("field", "city"));
            }
            receivedResumeInput.set("Hong Kong".equals(task.parameters().get("resumeUserMessage")));
            return AgentResult.success(task.taskId(), "done", "done");
        });
        InMemoryStore store = new InMemoryStore();
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("wait", List.of(
                node("collect", "collect", List.of())))));
        DagOrchestrationService service = service(planner, agent, store, false);

        DagRunOutcome waiting = service.execute(request("user-7", "book trip"), null).orElseThrow();
        DagRunOutcome completed = service.execute(request("user-7", "Hong Kong"), null).orElseThrow();

        assertThat(waiting.status()).isEqualTo(DagRunOutcome.Status.WAITING_USER);
        assertThat(waiting.messageToUser()).isEqualTo("need city");
        assertThat(completed.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(receivedResumeInput).isTrue();
        assertThat(store.findActive()).isEmpty();
    }

    @Test
    void requiresExplicitConfirmationBeforeRollingBackSideEffects() {
        AtomicInteger calls = new AtomicInteger();
        AgentUnit browser = namedAgent("BROWSER", task -> {
            calls.incrementAndGet();
            return AgentResult.success(task.taskId(), "published", "published");
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("publish", List.of(
                new DagNodeDraft("publish", "BROWSER", "publish", null, List.of(), Map.of())))));
        planner.reflections.add(new DagReflection(DagReflection.Action.ROLLBACK, "publish", "verify again",
                null, null, List.of()));
        InMemoryStore store = new InMemoryStore();
        DagOrchestrationService service = service(planner, List.of(browser), store, true);

        DagRunOutcome waiting = service.execute(request("user-8", "publish"), null).orElseThrow();
        DagRunOutcome stillWaiting = service.execute(request("user-8", "what happened?"), null).orElseThrow();
        DagRunOutcome completed = service.execute(request("user-8", "确认回退"), null).orElseThrow();

        assertThat(waiting.status()).isEqualTo(DagRunOutcome.Status.WAITING_USER);
        assertThat(stillWaiting.status()).isEqualTo(DagRunOutcome.Status.WAITING_USER);
        assertThat(calls).hasValue(2);
        assertThat(completed.status()).isEqualTo(DagRunOutcome.Status.COMPLETED);
    }

    @Test
    void pauseReturnsImmediatelyAndResumeSkipsCompletedNodes() throws Exception {
        CountDownLatch rootStarted = new CountDownLatch(1);
        CountDownLatch releaseRoot = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        AgentUnit agent = agent(task -> {
            if (task.instruction().equals("root")) {
                rootCalls.incrementAndGet();
                rootStarted.countDown();
                try {
                    releaseRoot.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                childCalls.incrementAndGet();
            }
            return AgentResult.success(task.taskId(), "ok", task.instruction());
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("pause", List.of(
                node("root", "root", List.of()),
                node("child", "child", List.of("root"))))));
        InMemoryStore store = new InMemoryStore();
        DagOrchestrationService service = service(planner, agent, store, false);

        DagOrchestrationService.DagSubmission submitted = service.submit(
                request("owner", "long task"), null, ignored -> { });
        assertThat(rootStarted.await(2, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        DagOrchestrationService.DagSubmission paused = service.handleUserMessage(
                request("owner", "暂停"), null, ignored -> { });
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(paused.status()).isEqualTo(
                DagOrchestrationService.DagSubmission.Status.CONTROLLED);
        assertThat(latencyMs).isLessThan(500);
        releaseRoot.countDown();
        awaitStatus(store, submitted.dagId(), DagTask.Status.PAUSED);
        assertThat(rootCalls).hasValue(1);
        assertThat(childCalls).hasValue(0);

        service.handleUserMessage(request("owner", "继续"), null,
                ignored -> completed.countDown());

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(rootCalls).hasValue(1);
        assertThat(childCalls).hasValue(1);
    }

    @Test
    void runsTwoIndependentDagTasksConcurrentlyAndFocusesLatest() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AgentUnit agent = agent(task -> {
            bothStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.success(task.taskId(), "done", "done");
        });
        ScriptedPlanner planner = new ScriptedPlanner(List.of(DagPlanDraft.dag("one", List.of(
                node("work", "work", List.of())))));
        InMemoryStore store = new InMemoryStore();
        DagOrchestrationService service = service(planner, agent, store, false);

        DagOrchestrationService.DagSubmission first = service.submit(
                request("owner", "first"), null, ignored -> completed.countDown());
        DagOrchestrationService.DagSubmission second = service.submit(
                request("owner", "second"), null, ignored -> completed.countDown());

        assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.activeDags()).hasSize(2);
        assertThat(first.dagId()).isNotEqualTo(second.dagId());
        assertThat(service.focusedContext().orElseThrow().dagId()).isEqualTo(second.dagId());

        release.countDown();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitStatus(InMemoryStore store, String dagId,
                                    DagTask.Status expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            DagTask task = store.findById(dagId).orElse(null);
            if (task != null && task.status() == expected) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("DAG " + dagId + " did not reach " + expected);
    }

    private DagOrchestrationService service(ScriptedPlanner planner, AgentUnit agent,
                                            DagTaskStore store, boolean reflectionEnabled) {
        return service(planner, List.of(agent), store, reflectionEnabled);
    }

    private DagOrchestrationService service(ScriptedPlanner planner, List<AgentUnit> agents,
                                            DagTaskStore store, boolean reflectionEnabled) {
        DagOrchestrationProperties properties = new DagOrchestrationProperties();
        properties.setReflectionEnabled(reflectionEnabled);
        properties.setDagPlanRepairAttempts(1);
        properties.setDagMaxWaves(10);
        properties.setDagMaxReplans(10);
        DagOrchestrationService service = new DagOrchestrationService(
                planner, new AgentRegistry(agents, null), store, properties);
        services.add(service);
        return service;
    }

    private static DagNodeDraft node(String key, String instruction, List<String> dependencies) {
        return new DagNodeDraft(key, "CHAT", instruction, null, dependencies, Map.of());
    }

    private static UserRequest request(String userId, String text) {
        return new UserRequest(userId, text, List.of(), List.of());
    }

    private static AgentUnit agent(Function<AgentTask, AgentResult> execute) {
        return namedAgent("CHAT", execute);
    }

    private static AgentUnit namedAgent(String name, Function<AgentTask, AgentResult> execute) {
        return new AgentUnit() {
            @Override public String getName() { return name; }
            @Override public AgentCapability getCapability() {
                return new AgentCapability(name, name, List.of(), "text");
            }
            @Override public AgentResult execute(AgentTask task) { return execute.apply(task); }
        };
    }

    private static final class ScriptedPlanner implements DagPlanningAgent {
        private final List<DagPlanDraft> plans;
        private final List<DagReflection> reflections = new ArrayList<>();
        private final AtomicInteger planCalls = new AtomicInteger();
        private final AtomicInteger reflectionCalls = new AtomicInteger();
        private List<String> lastValidationErrors = List.of();

        private ScriptedPlanner(List<DagPlanDraft> plans) {
            this.plans = plans;
        }

        @Override
        public DagPlanDraft planDag(UserRequest request, List<String> validationErrors) {
            int index = planCalls.getAndIncrement();
            lastValidationErrors = List.copyOf(validationErrors);
            return plans.get(Math.min(index, plans.size() - 1));
        }

        @Override
        public DagReflection reflectDag(UserRequest request, String snapshot, List<String> validationErrors) {
            int index = reflectionCalls.getAndIncrement();
            if (index < reflections.size()) return reflections.get(index);
            return new DagReflection(DagReflection.Action.CONTINUE, null, null, null, null, List.of());
        }
    }

    private static final class InMemoryStore implements DagTaskStore {
        private final Map<String, DagTask> workflows = new ConcurrentHashMap<>();

        @Override public void save(DagTask workflow) { workflows.put(workflow.dagId(), workflow); }

        @Override
        public List<DagTask> findActive() {
            return workflows.values().stream().filter(DagTask::isActive).toList();
        }

        @Override
        public Optional<DagTask> findFocused() {
            return workflows.values().stream()
                    .filter(DagTask::isActive).filter(DagTask::focused).findFirst();
        }

        @Override
        public void setFocused(String dagId) {
            workflows.values().forEach(task -> task.setFocused(task.dagId().equals(dagId)));
        }

        @Override public Optional<DagTask> findById(String workflowId) {
            return Optional.ofNullable(workflows.get(workflowId));
        }

        @Override
        public void recordAttempt(String workflowId, DagNode node, int attemptNo, long startedAt,
                                  long finishedAt, AgentResult result) {
        }
    }
}
