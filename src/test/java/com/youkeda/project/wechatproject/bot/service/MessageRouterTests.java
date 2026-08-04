package com.youkeda.project.wechatproject.bot.service;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentExecutionContext;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.orchestrator.DagOrchestrationProperties;
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.router.SimpleModeRouter;
import com.youkeda.project.wechatproject.bot.router.TaskComplexityRouter;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.chat.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.chat.ScheduledTaskExecutionRequest;
import com.youkeda.project.wechatproject.bot.workflow.DagNode;
import com.youkeda.project.wechatproject.bot.workflow.DagNodeDraft;
import com.youkeda.project.wechatproject.bot.workflow.DagOrchestrationService;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanDraft;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanningAgent;
import com.youkeda.project.wechatproject.bot.workflow.DagReflection;
import com.youkeda.project.wechatproject.bot.workflow.DagTask;
import com.youkeda.project.wechatproject.bot.workflow.DagTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRouterTests {

    @TempDir
    Path tempDir;

    @Test
    void scheduledTaskRunsThroughDag() throws IOException {
        RecordingAgent chat = new RecordingAgent("CHAT", "scheduled result");
        RecordingPlanner planner = new RecordingPlanner(request -> DagPlanDraft.dag(
                "scheduled", List.of(node("execute", "CHAT", "execute saved task", List.of()))));
        Fixture fixture = fixture(List.of(chat), planner, null, null);

        try {
            List<ModelReply> replies = fixture.router.routeScheduledTask(new ScheduledTaskExecutionRequest(
                    "task-1", "user-1", "weather", "check current weather",
                    "tell me the weather later", List.of("web"), Instant.now(), false));

            assertThat(planner.lastRequest.text()).contains("[scheduled-task-trigger]", "check current weather");
            assertThat(chat.instructions).containsExactly("execute saved task");
            assertThat(replies).anyMatch(reply -> "scheduled result".equals(reply.getTextContent())
                    || (reply.getTextContent() != null && reply.getTextContent().contains("scheduled result")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void stalePreparedFileDoesNotLeakIntoSimpleReply() throws Exception {
        Path staleFile = Files.writeString(tempDir.resolve("stale.txt"), "old file");
        LocalFileTools fileTools = new LocalFileTools();
        fileTools.setAllowedRoots(List.of(tempDir.toString()));
        fileTools.sendLocalFile(staleFile.toString());

        RecordingAgent chat = new RecordingAgent("CHAT", "plain chat reply");
        AgentRegistry registry = new AgentRegistry(List.of(chat), null);
        MessageRouter router = new MessageRouter(null, null,
                new SimpleModeRouter(registry, null), null,
                new TaskComplexityRouter(null, registry, null));

        List<ModelReply> replies = router.route("user-1", "你好", List.of(), null);

        assertThat(replies).singleElement().satisfies(reply -> {
            assertThat(reply.getType()).isEqualTo(ModelReply.Type.TEXT);
            assertThat(reply.getTextContent()).isEqualTo("plain chat reply");
            assertThat(reply.getFilePayload()).isNull();
        });
    }

    @Test
    void dagPassesLongChatOutputToDependentBrowserAsPayload() throws IOException {
        String article = "这是一段要发布到语雀的正文。".repeat(200);
        RecordingAgent chat = new RecordingAgent("CHAT", article);
        RecordingAgent browser = new RecordingAgent("BROWSER", "published");
        RecordingPlanner planner = new RecordingPlanner(request -> DagPlanDraft.dag("publish", List.of(
                node("write", "CHAT", "写文章", List.of()),
                node("publish", "BROWSER", "将 {{LAST_CHAT_TEXT}} 写入正文并发布", List.of("write")))));
        Fixture fixture = fixture(List.of(chat, browser), planner, null, null);

        try {
            List<ModelReply> replies = fixture.router.route(
                    "user-1", "写一篇文章然后发布到语雀", List.of(), null);

            assertThat(browser.instructions.getFirst()).contains("[TEXT_PAYLOAD:LAST_CHAT_TEXT]")
                    .doesNotContain(article);
            assertThat(browser.parameters.getFirst()).containsEntry("textPayload", article);
            assertThat(browser.executionContexts.getFirst().taskState().records())
                    .anySatisfy(record -> assertThat(record.result()).isEqualTo(article));
            assertThat(replies).anyMatch(reply -> reply.getTextContent() != null
                    && reply.getTextContent().contains("published"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void pausedNodePersistsAsWaitingDagAndConversationTurn() throws IOException {
        RecordingMemory memory = new RecordingMemory();
        AgentUnit browser = agent("BROWSER", task -> AgentResult.paused(
                task.taskId(), "please login first", Map.of("step", "login")));
        RecordingDagStore store = new RecordingDagStore(null);
        RecordingPlanner planner = new RecordingPlanner(request -> DagPlanDraft.dag(
                "browser", List.of(node("publish", "BROWSER", "publish", List.of()))));
        Fixture fixture = fixture(List.of(browser), planner, memory, store);

        try {
            List<ModelReply> replies = fixture.router.route(
                    "user-1", "publish the article", List.of(), null);

            assertThat(replies.getFirst().getTextContent()).isEqualTo("please login first");
            DagTask active = store.findActive().getFirst();
            assertThat(active.status()).isEqualTo(DagTask.Status.WAITING_USER);
            assertThat(active.nodes()).singleElement()
                    .satisfies(node -> assertThat(node.status()).isEqualTo(DagNode.Status.WAITING_USER));
            assertThat(memory.userMessages).containsExactly("publish the article");
            assertThat(memory.assistantReplies).containsExactly("please login first");
        } finally {
            fixture.close();
        }
    }

    @Test
    void focusedDagOwnsUnqualifiedMessageWithoutNewTopicFilter() throws Exception {
        DagTask active = new DagTask("D-OLDTASK1", "user-1", "old task");
        active.setFocused(true);
        RecordingDagStore store = new RecordingDagStore(active);
        RecordingPlanner planner = new RecordingPlanner(
                request -> DagPlanDraft.completed("new request", "new topic handled"));
        Fixture fixture = fixture(List.of(), planner, null, store, null);
        CountDownLatch completed = new CountDownLatch(1);

        try {
            List<ModelReply> replies = fixture.router.route(
                    "user-1", "补充一个要求", List.of(), null,
                    ignored -> completed.countDown());

            assertThat(replies.getFirst().getTextContent()).contains("已记录新要求");
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(active.status()).isEqualTo(DagTask.Status.SUCCEEDED);
        } finally {
            fixture.close();
        }
    }

    @Test
    void automaticComplexityRoutingKeepsSimpleChatOutOfDag() throws IOException {
        RecordingAgent chat = new RecordingAgent("CHAT", "simple reply");
        RecordingPlanner planner = new RecordingPlanner(
                request -> DagPlanDraft.completed("complex", "dag reply"));
        AgentRegistry registry = new AgentRegistry(List.of(chat), null);
        TaskComplexityRouter complexity = new TaskComplexityRouter(null, registry, null);
        Fixture fixture = fixture(List.of(chat), planner, null, null, complexity);

        try {
            List<ModelReply> simple = fixture.router.route("simple", "你好", List.of(), null);
            List<ModelReply> complex = fixture.router.route(
                    "complex", "先写一篇文章，然后发布到语雀", List.of(), null);

            assertThat(simple.getLast().getTextContent()).isEqualTo("simple reply");
            assertThat(complex.getLast().getTextContent()).isEqualTo("dag reply");
            assertThat(planner.planCalls).hasValue(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void dagPlanningFailureReturnsExplicitRetryableFallback() throws IOException {
        RecordingPlanner planner = new RecordingPlanner(
                request -> DagPlanDraft.unavailable("provider unavailable"));
        Fixture fixture = fixture(List.of(), planner, null, null);

        try {
            List<ModelReply> replies = fixture.router.route(
                    "user-1", "完成一个复杂任务", List.of(), null);

            assertThat(replies).singleElement()
                    .satisfies(reply -> assertThat(reply.getTextContent())
                            .contains("复杂任务服务暂时不可用", "稍后重试"));
        } finally {
            fixture.close();
        }
    }

    private Fixture fixture(List<AgentUnit> agents, RecordingPlanner planner,
                             ConversationMemory memory, RecordingDagStore store) {
        return fixture(agents, planner, memory, store, null);
    }

    private Fixture fixture(List<AgentUnit> agents, RecordingPlanner planner,
                             ConversationMemory memory, RecordingDagStore suppliedStore,
                             TaskComplexityRouter complexityRouter) {
        AgentRegistry registry = new AgentRegistry(agents, null);
        RecordingDagStore store = suppliedStore != null ? suppliedStore : new RecordingDagStore(null);
        DagOrchestrationProperties properties = new DagOrchestrationProperties();
        properties.setReflectionEnabled(false);
        DagOrchestrationService dagService = new DagOrchestrationService(
                planner, registry, store, properties);
        SimpleModeRouter simpleRouter = new SimpleModeRouter(registry, memory);
        MessageRouter router = new MessageRouter(
                memory, null, simpleRouter, dagService, complexityRouter);
        return new Fixture(router, dagService);
    }

    private static DagNodeDraft node(String key, String agent, String instruction,
                                     List<String> dependencies) {
        return new DagNodeDraft(key, agent, instruction, null, dependencies, Map.of());
    }

    private static AgentUnit agent(String name, Function<AgentTask, AgentResult> execution) {
        return new AgentUnit() {
            @Override public String getName() { return name; }
            @Override public AgentCapability getCapability() {
                return new AgentCapability(name, name, List.of(), "text");
            }
            @Override public AgentResult execute(AgentTask task) { return execution.apply(task); }
        };
    }

    private static final class RecordingAgent implements AgentUnit {
        private final String name;
        private final String response;
        private final List<String> instructions = new ArrayList<>();
        private final List<Map<String, Object>> parameters = new ArrayList<>();
        private final List<AgentExecutionContext> executionContexts = new ArrayList<>();

        private RecordingAgent(String name, String response) {
            this.name = name;
            this.response = response;
        }

        @Override public String getName() { return name; }
        @Override public AgentCapability getCapability() {
            return new AgentCapability(name, name, List.of(), "text", List.of(name.toLowerCase()));
        }
        @Override public AgentResult execute(AgentTask task) {
            instructions.add(task.instruction());
            parameters.add(task.parameters());
            executionContexts.add(task.executionContext());
            return AgentResult.success(task.taskId(), response, response);
        }
    }

    private static final class RecordingPlanner implements DagPlanningAgent {
        private final Function<UserRequest, DagPlanDraft> plan;
        private final AtomicInteger planCalls = new AtomicInteger();
        private UserRequest lastRequest;

        private RecordingPlanner(Function<UserRequest, DagPlanDraft> plan) {
            this.plan = plan;
        }

        @Override public DagPlanDraft planDag(UserRequest request, List<String> errors) {
            lastRequest = request;
            planCalls.incrementAndGet();
            return plan.apply(request);
        }
        @Override public DagReflection reflectDag(UserRequest request, String snapshot, List<String> errors) {
            return new DagReflection(DagReflection.Action.CONTINUE, null, null,
                    null, null, List.of());
        }
    }

    private static final class RecordingMemory implements ConversationMemory {
        private final List<String> userMessages = new ArrayList<>();
        private final List<String> assistantReplies = new ArrayList<>();

        @Override public List<ChatRequest.Message> getHistory(String userId) { return List.of(); }
        @Override public void append(String userId, String userMessage, String assistantReply) {
            userMessages.add(userMessage);
            assistantReplies.add(assistantReply);
        }
        @Override public void appendUserMessage(String userId, String userMessage) {
            userMessages.add(userMessage);
        }
        @Override public void clear(String userId) { }
    }

    private static final class RecordingDagStore implements DagTaskStore {
        private final Map<String, DagTask> workflows = new ConcurrentHashMap<>();

        private RecordingDagStore(DagTask workflow) {
            if (workflow != null) save(workflow);
        }

        @Override public void save(DagTask workflow) {
            workflows.put(workflow.dagId(), workflow);
        }
        @Override public List<DagTask> findActive() {
            return workflows.values().stream().filter(DagTask::isActive).toList();
        }
        @Override public Optional<DagTask> findFocused() {
            return workflows.values().stream()
                    .filter(DagTask::isActive).filter(DagTask::focused).findFirst();
        }
        @Override public void setFocused(String dagId) {
            workflows.values().forEach(task -> task.setFocused(task.dagId().equals(dagId)));
        }
        @Override public Optional<DagTask> findById(String workflowId) {
            return Optional.ofNullable(workflows.get(workflowId));
        }
        @Override public void recordAttempt(String workflowId, DagNode node, int attemptNo,
                                            long startedAt, long finishedAt, AgentResult result) { }
    }

    private record Fixture(MessageRouter router, DagOrchestrationService dagService) implements AutoCloseable {
        @Override public void close() { dagService.shutdown(); }
    }
}
