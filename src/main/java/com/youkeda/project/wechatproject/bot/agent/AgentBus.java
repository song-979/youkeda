package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.tool.chat.RagTools;
import com.youkeda.project.wechatproject.bot.tool.travel.DiDiTaxiTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Inter-agent communication bus.
 * <p>
 * Allows agents to delegate subtasks to other agents without going through
 * the Orchestrator. Supports both sequential and parallel delegation.
 */
public class AgentBus {

    private static final Logger log = LoggerFactory.getLogger(AgentBus.class);
    private final AgentRegistry registry;
    private final ExecutorService executor;

    public AgentBus(AgentRegistry registry) {
        this.registry = registry;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Synchronously delegate a task to another agent and wait for the result.
     * <p>
     * If the task carries a userId, the per-user tool context (RAG / DiDi thread-locals)
     * is installed for the duration of the call. This is what makes context propagation
     * work on virtual threads spawned by {@link #delegateParallel(Map)}, where
     * thread-locals from the caller thread would otherwise be lost.
     */
    public AgentResult delegate(String agentName, AgentTask task) {
        AgentUnit target = registry.get(agentName);
        log.info("AgentBus: delegating to {} (instruction preview: {})",
                agentName, preview(task.instruction()));
        boolean contextInstalled = installUserContext(task.userId());
        try {
            AgentResult result = target.execute(task);
            log.info("AgentBus: {} result status={}", agentName, result.status());
            return result;
        } catch (IOException e) {
            log.error("AgentBus: {} delegation failed: {}", agentName, e.getMessage());
            return AgentResult.failed(task.taskId(), agentName + " delegation failed: " + e.getMessage());
        } finally {
            if (contextInstalled) {
                RagTools.clearCurrentUser();
                DiDiTaxiTools.clearCurrentUser();
            }
        }
    }

    private boolean installUserContext(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        RagTools.setCurrentUser(userId);
        DiDiTaxiTools.setCurrentUser(userId);
        return true;
    }

    /**
     * Delegate tasks to multiple agents in parallel and wait for all results.
     */
    public Map<String, AgentResult> delegateParallel(Map<String, AgentTask> tasks) {
        Map<String, AgentResult> results = new LinkedHashMap<>();
        Map<String, CompletableFuture<AgentResult>> futures = new LinkedHashMap<>();

        for (var entry : tasks.entrySet()) {
            futures.put(entry.getKey(), CompletableFuture.supplyAsync(
                    () -> delegate(entry.getKey(), entry.getValue()), executor));
        }

        for (var entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.error("AgentBus: parallel delegation to {} failed: {}", entry.getKey(), e.getMessage());
                results.put(entry.getKey(),
                        AgentResult.failed("parallel-" + entry.getKey(), e.getMessage()));
            }
        }
        return results;
    }

    private static String preview(String instruction) {
        if (instruction == null) return "null";
        return instruction.length() > 60 ? instruction.substring(0, 60) + "..." : instruction;
    }
}
