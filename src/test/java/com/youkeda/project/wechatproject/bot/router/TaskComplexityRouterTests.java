package com.youkeda.project.wechatproject.bot.router;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatCallOptions;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskComplexityRouterTests {

    private final AgentRegistry registry = new AgentRegistry(List.of(
            agent("CHAT", List.of("提醒", "写文章")),
            agent("TRAVEL", List.of("天气", "路线")),
            agent("IMAGE_GEN", List.of("生成图片", "画一张"))), null);

    @Test
    void rulesHandleObviousSimpleAndComplexRequestsWithoutModelCall() {
        CapturingClient client = new CapturingClient();
        TaskComplexityRouter router = new TaskComplexityRouter(client, registry, "deepseek-chat");

        TaskComplexityRouter.Assessment greeting = router.assess(request("你好"));
        TaskComplexityRouter.Assessment reminder = router.assess(request("提醒我下午三点开会"));
        TaskComplexityRouter.Assessment workflow = router.assess(
                request("先查询明天杭州天气，然后生成一张出行提示图片"));

        assertThat(greeting.isSimple()).isTrue();
        assertThat(reminder.isSimple()).isTrue();
        assertThat(workflow.complexity()).isEqualTo(TaskComplexityRouter.Complexity.COMPLEX);
        assertThat(workflow.reasonCode()).isIn("MULTIPLE_AGENT_DOMAINS", "EXPLICIT_MULTI_STEP");
        assertThat(client.calls).isZero();
    }

    @Test
    void ambiguousRequestUsesSmallModelWithBoundedOutput() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"complexity\":\"COMPLEX\",\"reason_code\":\"DEPENDENT_TASKS\"}";
        TaskComplexityRouter router = new TaskComplexityRouter(client, registry, "deepseek-chat");

        TaskComplexityRouter.Assessment assessment = router.assess(
                request("请为这个需求制定完整方案，包含数据准备和最终交付要求"));

        assertThat(assessment.complexity()).isEqualTo(TaskComplexityRouter.Complexity.COMPLEX);
        assertThat(assessment.source()).isEqualTo(TaskComplexityRouter.Source.MODEL);
        assertThat(assessment.reasonCode()).isEqualTo("DEPENDENT_TASKS");
        assertThat(client.options.model()).isEqualTo("deepseek-chat");
        assertThat(client.options.maxTokens()).isEqualTo(96);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void modelFailureFallsBackAndOpensCircuitWithoutRetrying() {
        CapturingClient client = new CapturingClient();
        client.failure = new IOException("classifier timeout");
        TaskComplexityRouter router = new TaskComplexityRouter(client, registry, "deepseek-chat");
        UserRequest ambiguous = request("请制定一套计划，同时考虑资源限制和交付质量");

        TaskComplexityRouter.Assessment first = router.assess(ambiguous);
        TaskComplexityRouter.Assessment second = router.assess(ambiguous);

        assertThat(first.source()).isEqualTo(TaskComplexityRouter.Source.FALLBACK);
        assertThat(second.source()).isEqualTo(TaskComplexityRouter.Source.FALLBACK);
        assertThat(client.calls).isEqualTo(1);
    }

    private static UserRequest request(String text) {
        return new UserRequest("user", text, List.of(), List.of());
    }

    private static AgentUnit agent(String name, List<String> keywords) {
        return new AgentUnit() {
            @Override public String getName() { return name; }

            @Override
            public AgentCapability getCapability() {
                return new AgentCapability(name.toLowerCase(), name, List.of(), "text", keywords, true);
            }

            @Override
            public AgentResult execute(AgentTask task) {
                return AgentResult.success(task.taskId(), "ok", "ok");
            }
        };
    }

    private static final class CapturingClient implements AiModelClient {
        private int calls;
        private String response = "{\"complexity\":\"SIMPLE\",\"reason_code\":\"SINGLE_TASK\"}";
        private IOException failure;
        private ChatCallOptions options;

        @Override
        public String chat(String userMessage, List<String> images,
                           List<ChatRequest.Message> history) {
            throw new AssertionError("structured message API expected");
        }

        @Override
        public String chat(List<ChatRequest.Message> messages, ChatCallOptions options) throws IOException {
            calls++;
            this.options = options;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
