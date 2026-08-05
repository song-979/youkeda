package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.context.ContextEngineeringProperties;
import com.youkeda.project.wechatproject.bot.context.ToolLoopContextManager;
import com.youkeda.project.wechatproject.bot.context.ToolTranscriptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decorates a {@link ChatModel} with per-call (per-round) timeout.
 * <p>
 * Each {@link #call(Prompt)} invocation corresponds to one round of the Spring AI
 * tool-calling loop (LLM reasons → returns tool calls or final text). The timeout
 * applies to this single round, not the cumulative total of all rounds.
 * <p>
 * On timeout, returns a graceful text response that stops the tool loop instead
 * of throwing an exception that would kill the entire agent session.
 * <p>
 * Uses {@link CompletableFuture#supplyAsync} with {@link ForkJoinPool#commonPool()}
 * (JVM-shared pool, no thread explosion per bot instance).
 */
public class TimeoutChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TimeoutChatModel.class);

    private final ChatModel delegate;
    private final long perRoundTimeoutSeconds;
    private final int maxRounds;
    private final int maxContextTokens;
    private final ToolLoopContextManager contextManager;
    private final ThreadLocal<Integer> currentRound = ThreadLocal.withInitial(() -> 0);

    public TimeoutChatModel(ChatModel delegate, long perRoundTimeoutSeconds) {
        this(delegate, perRoundTimeoutSeconds, 20, 30000);
    }

    public TimeoutChatModel(ChatModel delegate, long perRoundTimeoutSeconds,
                            int maxRounds, int maxContextTokens) {
        this(delegate, perRoundTimeoutSeconds, maxRounds, maxContextTokens,
                new ToolLoopContextManager(ToolTranscriptStore.noop(), maxContextTokens,
                        ContextEngineeringProperties.defaults()));
    }

    public TimeoutChatModel(ChatModel delegate, long perRoundTimeoutSeconds,
                            int maxRounds, int maxContextTokens,
                            ToolLoopContextManager contextManager) {
        this.delegate = delegate;
        this.perRoundTimeoutSeconds = perRoundTimeoutSeconds;
        this.maxRounds = Math.max(1, maxRounds);
        this.maxContextTokens = Math.max(1024, maxContextTokens);
        this.contextManager = contextManager;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        boolean continuation = prompt.getInstructions().stream()
                .anyMatch(ToolResponseMessage.class::isInstance);
        int round = continuation ? currentRound.get() + 1 : 1;
        currentRound.set(round);
        if (round > maxRounds) {
            log.warn("tool loop stopped after reaching {} model rounds", maxRounds);
            clearLoopState();
            return buildGuardResponse("工具调用已达到最大轮数（" + maxRounds + " 轮），已停止继续执行。请缩小任务范围后重试。");
        }

        ToolLoopContextManager.PreparedPrompt prepared = contextManager.prepare(prompt, continuation, round);
        Prompt effectivePrompt = prepared.prompt();
        int promptTokens = prepared.preparedTokens();
        if (promptTokens > maxContextTokens) {
            log.warn("tool loop stopped because prompt reached {} tokens (limit={})",
                    promptTokens, maxContextTokens);
            clearLoopState();
            return buildGuardResponse("工具调用上下文已达到 token 上限，已停止继续执行。请拆分任务后重试。");
        }

        if (perRoundTimeoutSeconds <= 0) {
            ChatResponse response = delegate.call(effectivePrompt);
            clearIfComplete(response);
            return response;
        }
        try {
            ChatResponse response = CompletableFuture
                    .supplyAsync(() -> delegate.call(effectivePrompt))
                    .orTimeout(perRoundTimeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof CancellationException
                                || ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                            log.warn("ChatModel call timed out after {}s per-round limit", perRoundTimeoutSeconds);
                        } else {
                            log.warn("ChatModel call failed: {}", ex.getMessage());
                        }
                        return buildTimeoutResponse();
                    })
                    .get();
            clearIfComplete(response);
            return response;
        } catch (Exception e) {
            log.warn("TimeoutChatModel unexpected error: {}", e.getMessage());
            clearLoopState();
            return buildTimeoutResponse();
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ChatResponse buildTimeoutResponse() {
        return buildGuardResponse("当前步骤执行超时（" + perRoundTimeoutSeconds
                + "秒），已停止继续执行。请重新描述需求或分步执行。");
    }

    private ChatResponse buildGuardResponse(String msg) {
        Generation generation = new Generation(new AssistantMessage(msg));
        return new ChatResponse(List.of(generation));
    }

    private void clearIfComplete(ChatResponse response) {
        if (response == null || response.getResult() == null
                || !(response.getResult().getOutput() instanceof AssistantMessage assistant)
                || !assistant.hasToolCalls()) {
            contextManager.complete();
        }
    }

    private void clearLoopState() {
        currentRound.remove();
        contextManager.complete();
    }
}
