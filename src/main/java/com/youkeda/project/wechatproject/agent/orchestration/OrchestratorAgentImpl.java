package com.youkeda.project.wechatproject.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.agent.AgentProperties;
import com.youkeda.project.wechatproject.agent.ChatRequest;
import com.youkeda.project.wechatproject.agent.ChatResponse;

import com.youkeda.project.wechatproject.agent.ModelReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrchestratorAgentImpl implements OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgentImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String PLAN_SYSTEM_PROMPT = """
            You are an orchestration model for a multi-agent assistant.
            Your system prompt contains the complete list of all available agent capabilities and TTS voices.
            Return JSON only, with no markdown and no extra text.

            Available agent units:
            %s

            Goals:
            1. Understand the user's intent from the current message and history.
            2. Decide whether clarification is required.
            3. Break the work into executable tasks when needed.

            Core principle — when to route vs answer directly:
            - You are the most knowledgeable component. Your prompt contains ALL capability/voice information.
            - If the user asks an informational question you can answer from your prompt context (e.g. available voices, what features are supported, how something works), return completed with your answer directly. Do NOT route these to CHAT.
            - Only route to sub-agents when their unique capability is needed:
              * CHAT: content generation (creative writing, analysis of user-provided images, open-ended conversation, generating text that will be spoken, AND generating file content — see file generation rules below)
              * IMAGE_GEN: generating new images
              * SPEECH_GEN: converting text to audio/speech

            File generation rules (via CHAT):
            - When the user asks to generate, export, save, download, or create a file (e.g. Markdown doc, report, summary, data export, weekly report, code file, etc.), route to CHAT.
            - The CHAT agent must output the file content wrapped in a structured marker:
              [FILE:filename.ext]
              file content here...
              [/FILE]
            - After the [/FILE] marker, the CHAT agent may include an optional plain-text response (e.g. "文件已生成"). This text will be sent alongside the file.
            - Supported file extensions: txt, md, json, csv, html, xml, log, docx. Choose the extension based on what the user asked for (e.g. .md for markdown documents, .docx for Word documents, .csv for tabular data, .json for structured data).
            - The filename should be descriptive and match the user's request (e.g. 周报.md, 数据分析报告.docx, 用户列表.csv).

            Input format — the user message may contain source annotations:
            - 【用户上传文件】filename（类型）: the user uploaded a file. The content that follows was extracted from this file.
              * For documents (Word/PDF/txt): the text content and possibly embedded images were extracted.
              * For audio files (MP3/WAV/etc): the text is a speech-to-text transcription result.
              * ⚠️ If the annotation says "文件解析失败", processing failed. Tell the user directly (completed status) what went wrong. Do NOT route to any sub-agent.
            - 【用户语音消息】: the user sent a WeChat voice message. The text is the speech-to-text transcription.
            - 【用户发送了图片】: the user sent images directly (not from a file). Route to CHAT for visual understanding.
            - 【用户消息】: the user's own typed text message.

            Task routing rules:
            - Output one JSON object only.
            - Supported statuses: needs_clarification, execute, completed.
            - If the user wants an image and then a description or analysis of that generated image, plan multiple tasks.
            - When the user refers to prior context, use the conversation history.
            - For file uploads with successfully extracted content: treat the extracted text + images as the user's input and route to CHAT for analysis.
            - For file uploads with ⚠️ failure annotations: DO NOT route to CHAT. Return completed with a helpful message explaining the issue.
            - For voice messages: treat the transcription as user input and route to CHAT normally.
            - For SPEECH_GEN tasks:
              * instruction MUST contain ONLY the raw text to speak. No narration, no stage directions, no "请朗读", no "停顿一秒", no markup of any kind. It will be sent verbatim to a TTS engine.
              * Detect the user's emotional state from conversation context.
              * Choose a voice that matches the user's mood and the content (see voice list and mood mapping above).
              * Set the voice via a "voice" parameter (use the exact voice ID).
              * When the user explicitly asks for a specific voice name, dialect, or gender, use that.
              * For Chinese dialects (Cantonese, Sichuan, Beijing, etc.) pick the matching dialect voice.
            - You may include an optional "parameters" object on each task.

            Output schemas:
            {"status":"needs_clarification","reasoning":"...","question":"..."}
            {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
            {"status":"completed","reasoning":"...","final_reply":"..."}
            """;

    private static final String REFLECT_SYSTEM_PROMPT = """
            You are reviewing completed subtask results for a multi-agent assistant.
            Return JSON only, with no markdown and no extra text.

            Available agent units:
            %s

            Decide one of the following:
            - completed: the current results are enough to answer the user
            - execute: more tasks are required

            Rules:
            - If the task results already satisfy the request, return completed.
            - If another step is needed, return execute with the next task list.
            - Prefer using the actual CHAT output as final_reply when it already answers the user.
            - If image or speech generation already succeeded, do not ask to regenerate unless there is a clear failure.
            - For new SPEECH_GEN tasks, follow the same voice selection rules as in planning.
            - For SPEECH_GEN tasks: instruction must be ONLY raw text to speak, no directions or markup.
            - If the user asked for a file and the CHAT output contains [FILE:...]...[/FILE] markers, the file was already generated successfully — do not route again.
            - You may include an optional "parameters" object on each task.

            Output schemas:
            {"status":"completed","reasoning":"...","final_reply":"..."}
            {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
            """;

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final AgentRegistry registry;
    private final String capabilitiesPrompt;

    public OrchestratorAgentImpl(AgentProperties props, AgentRegistry registry) {
        this.registry = registry;
        this.capabilitiesPrompt = registry.generateCapabilitiesPrompt();
        this.apiUrl = props.getIntentApiUrl() != null && !props.getIntentApiUrl().isEmpty()
                ? props.getIntentApiUrl() : props.getApiUrl();
        this.apiKey = props.getIntentApiKey() != null && !props.getIntentApiKey().isEmpty()
                ? props.getIntentApiKey() : props.getApiKey();
        this.model = props.getIntentModel() != null && !props.getIntentModel().isEmpty()
                ? props.getIntentModel() : props.getModel();
        this.restTemplate = createRestTemplate(
                props.getConnectTimeoutMs() > 0 ? props.getConnectTimeoutMs() : 5000,
                props.getReadTimeoutMs() > 0 ? props.getReadTimeoutMs() : 30000);
    }

    private static RestTemplate createRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    @Override
    public OrchestrationResult plan(UserRequest request) {
        String text = request.text();
        if ((text == null || text.isBlank()) && request.imageBase64Urls().isEmpty()) {
            return fallbackPlan(text);
        }

        try {
            return doPlan(request);
        } catch (Exception e) {
            log.warn("Orchestrator plan() failed, using fallback. error={}", e.getMessage());
            return fallbackPlan(text);
        }
    }

    private OrchestrationResult doPlan(UserRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", String.format(PLAN_SYSTEM_PROMPT, capabilitiesPrompt)));

        for (ChatRequest.Message msg : request.history()) {
            messages.add(Map.of(
                    "role", msg.getRole(),
                    "content", String.valueOf(msg.getContent())));
        }

        StringBuilder userContent = new StringBuilder();
        userContent.append(request.text() != null ? request.text() : "");
        if (!request.imageBase64Urls().isEmpty()) {
            userContent.append("\n[user attached images: ").append(request.imageBase64Urls().size()).append("]");
        }
        if (request.rememberedImageSummary() != null && !request.rememberedImageSummary().isBlank()) {
            userContent.append("\n[remembered image summary] ").append(request.rememberedImageSummary());
        }
        messages.add(Map.of("role", "user", "content", userContent.toString()));

        String response = callModel(messages);
        return parsePlanResponse(response, request.text());
    }

    @Override
    public OrchestrationResult reflect(TaskScratchpad scratchpad) {
        try {
            return doReflect(scratchpad);
        } catch (Exception e) {
            log.warn("Orchestrator reflect() failed, treating as completed. error={}", e.getMessage());
            return buildCompletedResult(scratchpad);
        }
    }

    private OrchestrationResult doReflect(TaskScratchpad scratchpad) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", String.format(REFLECT_SYSTEM_PROMPT, capabilitiesPrompt)));
        messages.add(Map.of("role", "user", "content", scratchpad.toReflectPrompt()));

        String response = callModel(messages);
        return parseReflectResponse(response, scratchpad);
    }

    private String callModel(List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.0);
        body.put("max_tokens", 800);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(apiUrl, entity, ChatResponse.class);
        ChatResponse chatResponse = response.getBody();

        if (chatResponse == null) {
            throw new RuntimeException("empty response from orchestrator model");
        }

        String content = chatResponse.extractContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("empty content in orchestrator response");
        }

        log.debug("orchestrator raw response: {}", content);
        return content;
    }

    @SuppressWarnings("unchecked")
    private OrchestrationResult parsePlanResponse(String content, String originalText) {
        String json = extractJson(content);
        if (json == null) {
            log.warn("cannot extract JSON from orchestrator plan response: {}", content);
            return fallbackPlan(originalText);
        }

        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            String status = (String) map.get("status");
            String reasoning = (String) map.getOrDefault("reasoning", "");

            if ("needs_clarification".equals(status)) {
                String question = (String) map.getOrDefault("question", "请再具体描述一下你的需求。");
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.NEEDS_CLARIFICATION)
                        .reasoning(reasoning)
                        .clarificationQuestion(question)
                        .build();
            }

            if ("execute".equals(status)) {
                List<AgentTask> tasks = parseTasks(map);
                if (tasks.isEmpty()) {
                    return fallbackPlan(originalText);
                }
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.EXECUTE)
                        .reasoning(reasoning)
                        .tasks(tasks)
                        .build();
            }

            if ("completed".equals(status)) {
                String finalReply = (String) map.getOrDefault("final_reply", "处理完成。");
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.COMPLETED)
                        .reasoning(reasoning)
                        .finalReply(ModelReply.text(finalReply))
                        .build();
            }

            return fallbackPlan(originalText);
        } catch (Exception e) {
            log.warn("failed to parse orchestrator JSON: json={}, error={}", json, e.getMessage());
            return fallbackPlan(originalText);
        }
    }

    @SuppressWarnings("unchecked")
    private OrchestrationResult parseReflectResponse(String content, TaskScratchpad scratchpad) {
        String json = extractJson(content);
        if (json == null) {
            log.warn("cannot extract JSON from orchestrator reflect response: {}", content);
            return buildCompletedResult(scratchpad);
        }

        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            String status = (String) map.get("status");
            String reasoning = (String) map.getOrDefault("reasoning", "");

            if ("execute".equals(status)) {
                List<AgentTask> tasks = parseTasks(map);
                if (tasks.isEmpty()) {
                    return buildCompletedResult(scratchpad);
                }
                return OrchestrationResult.builder()
                        .status(OrchestrationResult.Status.EXECUTE)
                        .reasoning(reasoning)
                        .tasks(tasks)
                        .scratchpad(scratchpad)
                        .build();
            }

            String finalReply = (String) map.getOrDefault("final_reply", "任务已完成。");
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.COMPLETED)
                    .reasoning(reasoning)
                    .finalReply(ModelReply.text(finalReply))
                    .scratchpad(scratchpad)
                    .build();
        } catch (Exception e) {
            log.warn("failed to parse reflect JSON: json={}, error={}", json, e.getMessage());
            return buildCompletedResult(scratchpad);
        }
    }

    @SuppressWarnings("unchecked")
    private List<AgentTask> parseTasks(Map<String, Object> map) {
        List<AgentTask> tasks = new ArrayList<>();
        Object tasksObj = map.get("tasks");
        if (!(tasksObj instanceof List<?> list)) {
            return tasks;
        }

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> taskMap)) {
                continue;
            }

            String agentType = taskMap.get("agent_type") instanceof String s ? s : null;
            String instruction = taskMap.get("instruction") instanceof String s ? s : null;
            if (agentType == null || agentType.isBlank() || instruction == null || instruction.isBlank()) {
                continue;
            }

            Map<String, Object> parameters = Map.of();
            Object parametersObj = taskMap.get("parameters");
            if (parametersObj instanceof Map<?, ?> rawParams && !rawParams.isEmpty()) {
                Map<String, Object> copied = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawParams.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        copied.put(key, entry.getValue());
                    }
                }
                parameters = Map.copyOf(copied);
            }

            tasks.add(new AgentTask(agentType, instruction, parameters));
        }

        return tasks;
    }

    private OrchestrationResult fallbackPlan(String text) {
        // 编排模型不可用时，安全 fallback 到 CHAT。
        // 不在这里做 regex 意图识别——错误的路由比不做路由更糟糕。
        // 特殊意图（图片生成、语音合成）由 MessageRouter.specialCasePlan() 在调用编排器之前处理。
        return OrchestrationResult.builder()
                .status(OrchestrationResult.Status.EXECUTE)
                .reasoning("fallback: orchestrator model unavailable, default to chat")
                .tasks(List.of(new AgentTask("CHAT", text != null ? text : "", Map.of())))
                .build();
    }

    private OrchestrationResult buildCompletedResult(TaskScratchpad scratchpad) {
        String reply = scratchpad.lastSuccessfulChatText();
        if (reply == null || reply.isBlank()) {
            if (!scratchpad.successfulImageDataUrls().isEmpty()) {
                reply = "图片已生成。";
            } else {
                reply = "任务已完成。";
            }
        }

        return OrchestrationResult.builder()
                .status(OrchestrationResult.Status.COMPLETED)
                .reasoning("auto-completed from scratchpad")
                .finalReply(ModelReply.text(reply))
                .scratchpad(scratchpad)
                .build();
    }

    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start < 0 || end <= start) {
                return null;
            }
            trimmed = trimmed.substring(start + 1, end).trim();
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null;
        }
        return trimmed.substring(firstBrace, lastBrace + 1);
    }
}
