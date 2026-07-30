package com.youkeda.project.wechatproject.bot.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskScratchpad {

    private static final Logger log = LoggerFactory.getLogger(TaskScratchpad.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final List<ExecutionRecord> records = new ArrayList<>();

    public void record(AgentTask task, AgentResult result) {
        records.add(new ExecutionRecord(task, result));
    }

    public List<ExecutionRecord> records() {
        return List.copyOf(records);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** Returns true if any recorded task is in PAUSED state. */
    public boolean hasPausedTask() {
        return records.stream().anyMatch(r -> r.result().isPaused());
    }

    /** Returns the last PAUSED task record, or null. */
    public ExecutionRecord lastPausedTask() {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).result().isPaused()) {
                return records.get(i);
            }
        }
        return null;
    }

    /** Builds a resume prompt telling the orchestrator what was already completed and what's paused. */
    public String toResumePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 上一轮任务状态（可恢复） ===\n\n");
        sb.append("以下任务已在上一轮中完成，请勿重复规划：\n");
        boolean hasCompleted = false;
        for (int i = 0; i < records.size(); i++) {
            ExecutionRecord r = records.get(i);
            if (r.result().isPaused()) {
                sb.append("\n--- 中断点 ---\n");
                sb.append("Agent: ").append(r.task.agentType()).append("\n");
                sb.append("已提示用户: ").append(r.result.messageToUser()).append("\n");
                if (!r.result.resumeState().isEmpty()) {
                    sb.append("恢复状态: ").append(r.result.resumeState()).append("\n");
                }
            } else {
                hasCompleted = true;
                sb.append("- Agent: ").append(r.task.agentType())
                  .append(", 状态: ").append(r.result.status())
                  .append(", 指令: ").append(truncate(r.task.instruction(), 200)).append("\n");
                if (r.result.rawOutput() != null && !r.result.rawOutput().isEmpty()) {
                    sb.append("  输出摘要: ").append(truncate(r.result.rawOutput(), 300)).append("\n");
                }
            }
        }
        if (!hasCompleted) {
            sb.append("(无)\n");
        }
        sb.append("\n用户已回复。请根据需要规划后续任务（跳过已完成的）。\n");
        return sb.toString();
    }

    public String toReflectPrompt() {
        if (records.isEmpty()) {
            return "(no execution records)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Here are the subtask results:\n\n");
        for (int i = 0; i < records.size(); i++) {
            ExecutionRecord r = records.get(i);
            sb.append("--- Task ").append(i + 1).append(" ---\n");
            sb.append("Task ID: ").append(r.task.taskId()).append("\n");
            sb.append("Agent: ").append(r.task.agentType()).append("\n");
            sb.append("Instruction: ").append(r.task.instruction()).append("\n");
            sb.append("Status: ").append(r.result.status()).append("\n");
            if (r.result.rawOutput() != null && !r.result.rawOutput().isEmpty()) {
                sb.append("Output: ").append(r.result.rawOutput()).append("\n");
            }
            if (r.result.errorMessage() != null) {
                sb.append("Error: ").append(r.result.errorMessage()).append("\n");
            }
            if (r.result.isPaused()) {
                sb.append("Paused - message to user: ").append(r.result.messageToUser()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- JSON serialization for cross-turn persistence ----

    public String toJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExecutionRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", r.task.taskId());
            m.put("agentType", r.task.agentType());
            m.put("instruction", r.task.instruction());
            m.put("status", r.result.status().name());
            if (r.result.rawOutput() != null) {
                m.put("rawOutput", r.result.rawOutput());
            }
            if (r.result.errorMessage() != null) {
                m.put("errorMessage", r.result.errorMessage());
            }
            if (r.result.isPaused()) {
                m.put("messageToUser", r.result.messageToUser());
                m.put("resumeState", r.result.resumeState());
            }
            list.add(m);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("failed to serialize TaskScratchpad", e);
            return "[]";
        }
    }

    public static TaskScratchpad fromJson(String json) {
        TaskScratchpad sp = new TaskScratchpad();
        if (json == null || json.isBlank()) {
            return sp;
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : list) {
                String taskId = str(m, "taskId");
                String agentType = str(m, "agentType");
                String instruction = str(m, "instruction");
                String statusStr = str(m, "status");
                String rawOutput = str(m, "rawOutput");
                String errorMessage = str(m, "errorMessage");
                String messageToUser = str(m, "messageToUser");

                AgentTask task = new AgentTask(agentType, instruction, Map.of());
                AgentResult.Status status = AgentResult.Status.valueOf(statusStr);

                @SuppressWarnings("unchecked")
                Map<String, Object> resumeState = (Map<String, Object>) m.getOrDefault("resumeState", Map.of());

                AgentResult result = status == AgentResult.Status.PAUSED
                        ? AgentResult.paused(taskId, messageToUser, resumeState)
                        : new AgentResult(taskId, status, null, rawOutput, errorMessage);

                sp.record(task, result);
            }
        } catch (Exception e) {
            log.error("failed to deserialize TaskScratchpad: {}", json, e);
        }
        return sp;
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    public String lastSuccessfulChatText() {
        for (int i = records.size() - 1; i >= 0; i--) {
            ExecutionRecord r = records.get(i);
            if ("CHAT".equals(r.task().agentType())
                    && r.result().status() == AgentResult.Status.SUCCESS
                    && r.result().rawOutput() != null
                    && !r.result().rawOutput().isEmpty()) {
                return r.result().rawOutput();
            }
        }
        return null;
    }

    public List<String> allSuccessfulChatTexts() {
        List<String> texts = new ArrayList<>();
        for (ExecutionRecord r : records) {
            if ("CHAT".equals(r.task().agentType())
                    && r.result().status() == AgentResult.Status.SUCCESS
                    && r.result().rawOutput() != null
                    && !r.result().rawOutput().isEmpty()) {
                texts.add(r.result().rawOutput());
            }
        }
        return texts;
    }

    public String lastSuccessfulImageSummary() {
        for (int i = records.size() - 1; i >= 0; i--) {
            ExecutionRecord r = records.get(i);
            if ("IMAGE_GEN".equals(r.task().agentType())
                    && r.result().status() == AgentResult.Status.SUCCESS
                    && r.result().rawOutput() != null
                    && !r.result().rawOutput().isEmpty()) {
                return r.result().rawOutput();
            }
        }
        return null;
    }

    public List<String> successfulImageDataUrls() {
        List<String> urls = new ArrayList<>();
        for (ExecutionRecord r : records) {
            if (!"IMAGE_GEN".equals(r.task().agentType())) {
                continue;
            }
            if (r.result().status() != AgentResult.Status.SUCCESS) {
                continue;
            }
            Object output = r.result().output();
            if (output instanceof GeneratedImage image && image.bytes().length > 0) {
                urls.add(image.dataUrl());
            } else if (output instanceof byte[] bytes && bytes.length > 0) {
                urls.add("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
            }
        }
        return urls;
    }

    public record ExecutionRecord(AgentTask task, AgentResult result) {}
}
