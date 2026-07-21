package com.youkeda.project.wechatproject.agent.orchestration;

import com.youkeda.project.wechatproject.agent.GeneratedImage;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Task scratchpad for one orchestration round.
 */
public class TaskScratchpad {

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
            sb.append("\n");
        }
        return sb.toString();
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
