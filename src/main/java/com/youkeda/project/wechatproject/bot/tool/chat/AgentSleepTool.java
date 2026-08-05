package com.youkeda.project.wechatproject.bot.tool.chat;

import com.youkeda.project.wechatproject.bot.tool.ToolService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lets a DAG chat node checkpoint itself and continue after an external wait. */
public class AgentSleepTool implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSleepTool.class);
    private static final ThreadLocal<String> CURRENT_DAG = new ThreadLocal<>();
    private static final ThreadLocal<PendingSleep> PENDING_SLEEP = new ThreadLocal<>();

    private final AutomationRuntime automationRuntime;

    public AgentSleepTool(AutomationRuntime automationRuntime) {
        this.automationRuntime = automationRuntime;
    }

    @Override
    public String category() {
        return "automation";
    }

    public static void setCurrentDag(String dagId) {
        if (dagId == null || dagId.isBlank()) CURRENT_DAG.remove();
        else CURRENT_DAG.set(dagId);
    }

    public static void clearCurrentDag() {
        CURRENT_DAG.remove();
    }

    public static PendingSleep drain() {
        PendingSleep pending = PENDING_SLEEP.get();
        PENDING_SLEEP.remove();
        return pending;
    }

    public static void clear() {
        CURRENT_DAG.remove();
        PENDING_SLEEP.remove();
    }

    @Tool(name = "sleep_current_task",
            description = "Pause the current long-running DAG task until a future time when progress depends on waiting for time or external information. Do not use for ordinary chat or when user input is required.")
    public String sleepCurrentTask(
            @ToolParam(description = "Next wake time in ISO datetime format with timezone.") String wakeAt,
            @ToolParam(description = "Short factual reason why the task must wait.") String reason) {
        String dagId = CURRENT_DAG.get();
        String userId = AutomationRuntime.currentUserId();
        if (dagId == null || userId == null) {
            return "This tool is only available while executing a persistent DAG task.";
        }
        AutomationRuntime.HeartbeatResult result = automationRuntime.scheduleAgentWake(userId, wakeAt, reason);
        if (!result.success()) {
            return "Unable to pause the task: " + result.message();
        }
        PENDING_SLEEP.set(new PendingSleep(dagId, result.nextWakeAt(), reason));
        log.info("[HEARTBEAT] DAG self-sleep scheduled userId={} dagId={} nextWakeAt={} reason={}",
                userId, dagId, result.nextWakeAt(), reason);
        return "The DAG checkpoint will sleep until " + result.nextWakeAt()
                + ". Stop working and return the pause result now.";
    }

    public record PendingSleep(String dagId, java.time.Instant wakeAt, String reason) {
    }
}
