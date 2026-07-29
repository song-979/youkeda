package com.youkeda.project.wechatproject.bot.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatResponse;
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
import java.util.Locale;
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
            - You do NOT execute tools yourself. CHAT may use an internal tool-calling loop for runtime information, system/environment lookups, and future integrations.
            - If the request requires runtime information, external actions, integration data, or a capability that may exist as an internal tool, route to CHAT. Do not claim the system cannot do it just because the tool list is not shown to you; CHAT will answer normally if a tool exists and explain the limitation if no suitable tool exists.
            - Only route to sub-agents when their unique capability is needed:
              * CHAT: content generation (creative writing, analysis of user-provided images, open-ended conversation, generating text that will be spoken, tool-assisted runtime tasks, AND generating file content — see file generation rules below. Check CHAT's internal tool categories in the agent list above to decide routing.)
              * TRAVEL: weather queries, place/POI search, nearby search, route planning (walking/transit/driving/bicycling), map/navigation, geocoding, and taxi/ride-hailing (price estimate, create order, query order, cancel, driver location). Route ALL location, map, navigation, weather, and taxi requests here.
              * BROWSER: web browsing and browser automation (opening web pages, website login/sign-in, filling forms, clicking elements, taking screenshots, extracting page content, capturing network requests). Route web browsing, website login, webpage interaction, and website automation requests here.
              * IMAGE_GEN: generating new static images only. NOT for GIFs, animated stickers, emoji packs, or 表情包 — those must route to CHAT (CHAT has media_generation tools).
              * SPEECH_GEN: converting text to audio/speech

            Location / POI / Map / Navigation / Weather routing rules (CRITICAL):
            - When the user asks about locations, places, POI, nearby search, directions, routes, navigation, maps, geocoding, weather, or coordinates, you MUST route to TRAVEL. TRAVEL has internal Amap (高德地图) and weather tools that handle all these queries.
            - Explicit examples that MUST go to TRAVEL: "附近咖啡店", "XX在哪里", "搜索XX地点", "去XX怎么走", "从A到B的路线", "周边有什么餐厅", "查一下XX的地址", "导航到XX", "XX的坐标", "今天天气", "明天天气怎么样", "杭州天气预报", any location/place name/weather query.
            - Do NOT return completed for these queries — TRAVEL must handle them via its tool loop.
            - Do NOT suggest the user search manually or use a web browser — TRAVEL's internal tools will provide the answer directly.

            Reminder / Schedule / Timer routing rules (CRITICAL):
            - When the user asks to set a reminder, timer, alarm, schedule, or calendar event, you MUST route to CHAT. CHAT has internal automation tools (create_reminder, create_schedule_item, create_recurring_reminder, etc.) that handle all reminder and scheduling operations.
            - Explicit examples that MUST go to CHAT: "提醒我XX", "设置闹钟", "定时XX", "X分钟后叫我", "创建日程", "明天X点提醒", "每天X点提醒", "帮我记一下XX", "几点叫我", any reminder/timer/alarm/schedule request.
            - The system DOES support reminders — do NOT say it doesn't. Route to CHAT and let CHAT handle it.
            - Do NOT return completed for these queries — CHAT must handle them via its internal tool loop.

            CRITICAL — TEXT_REMINDER vs LLM_TASK (you must understand this before writing CHAT instructions):
            CHAT supports TWO kinds of scheduled tasks. Your CHAT instruction determines which one CHAT will create.
            - TEXT_REMINDER: A fixed text message stored at creation time. When the time arrives, the stored text is sent verbatim. Use only for static reminders like "该吃药了", "记得带伞", "去开会".
            - LLM_TASK: An AI execution task that RUNS AT THE TRIGGER TIME. At the scheduled time, the AI wakes up, executes the instruction (query weather, search news, generate content, etc.) with fresh real-time data, and sends the result.
            Key distinction: If the task content depends on real-time data (weather, news, stock prices, search results, AI-generated content), it MUST be LLM_TASK. TEXT_REMINDER would send stale pre-recorded data.
            When writing CHAT instructions for time-based tasks, you MUST tell CHAT which kind to use:
              * Static content ("X分钟后提醒我XX", "明天X点提醒我吃药") → tell CHAT: "创建一个TEXT_REMINDER，message=固定文字"
              * Dynamic content ("X点帮我查天气", "明天X点搜新闻", "X点帮我生成XX") → tell CHAT: "创建一个LLM_TASK定时任务，到期时执行：查询天气/搜索新闻/生成内容。不要现在查询，让定时任务在触发时实时查询。"
            ⚠️ NEVER tell CHAT to "query now and put the result in the reminder text" for weather/search/news tasks. This produces stale data. Tell CHAT to create an LLM_TASK instead.

            DiDi Taxi / Ride-hailing routing rules:
            - When the user asks to hail a taxi, call a car, or request a ride, you MUST route to TRAVEL.
            - TRAVEL internally handles the full flow: price estimation → present car options → PAUSED to wait for user selection. After PAUSED, the system will resume when the user replies with their car type choice.
            - During resume: if you see a TRAVEL task that PAUSED for car selection, plan a new TRAVEL task to create the order with the user's chosen car type.
            - Do NOT plan a create_order task in the first turn — TRAVEL will PAUSE first to let the user choose.
            - Explicit examples that MUST go to TRAVEL: "打车到XX", "帮我叫车", "叫个车去XX", "从A打车到B", "帮我打车", any ride-hailing/taxi request.

            File generation rules (via CHAT):
            - When the user asks to generate, export, save, download, or create a file (e.g. Markdown doc, report, summary, data export, weekly report, code file, etc.), route to CHAT.
            - CRITICAL: When creating a CHAT task for file generation, your instruction MUST explicitly include the output format requirement. Tell CHAT to wrap the file content in markers:
              [FILE:filename.ext]
              file content here...
              [/FILE]
            - Example CHAT instruction for file generation: "请根据对话历史总结今天的内容，生成一个Markdown文档。你必须使用 [FILE:对话总结.md] 和 [/FILE] 标记包裹文件内容，[/FILE] 之后可附加简短说明。"
            - After the [/FILE] marker, the CHAT agent may include an optional plain-text response (e.g. "文件已生成"). This text will be sent alongside the file.
            - Supported file extensions: txt, md, json, csv, html, xml, log, docx. Choose the extension based on what the user asked for (e.g. .md for markdown documents, .docx for Word documents, .csv for tabular data, .json for structured data). IMPORTANT: Always use .docx for Word documents, never .doc.
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
              * IMPORTANT: If the text to speak depends on a prior CHAT task's output (e.g. "write a poem then recite it", "generate copy then read it aloud"), use the placeholder {{LAST_CHAT_TEXT}} as the instruction. The system will automatically replace it with the actual text from the last successful CHAT task.
              * Detect the user's emotional state from conversation context.
              * Choose a voice that matches the user's mood and the content (see voice list and mood mapping above).
              * Set the voice via a "voice" parameter (use the exact voice ID).
              * When the user explicitly asks for a specific voice name, dialect, or gender, use that.
              * For Chinese dialects (Cantonese, Sichuan, Beijing, etc.) pick the matching dialect voice.
            - You may include an optional "parameters" object on each task.

            Browser / Webpage / Web Automation routing rules (CRITICAL):
            - When the user asks to browse a website, open a webpage, login to a website, search on a specific site, fill a form, take a screenshot, or perform any web automation, you MUST route to BROWSER. BROWSER has internal Chrome DevTools MCP tools (browser_navigate, browser_click, browser_fill, browser_screenshot, browser_snapshot, browser_request_human_login, etc.).
            - Explicit examples that MUST go to BROWSER: "打开XX网站", "登录XX", "帮我在XX登录", "打开XX并登录", "帮我在XX搜一下", "浏览XX网页", "帮我在XX网站填表", "截图XX页面", "打开XX并截图", "帮我看一下XX网站的内容", any website interaction, website login/sign-in, or web browsing request.
            - ⚠️ WEBSITE LOGIN: When the user wants to login to a website (e.g. "登录语雀", "登录XX账号", "打开XX并登录"), you MUST route to BROWSER. BROWSER has the browser_request_human_login tool that opens the login page in a visible browser window, prompts the human user to complete login manually (scan QR code or enter credentials), and then continues automation. Do NOT return needs_clarification or completed for login requests — BROWSER handles the entire login flow.
            - Do NOT route simple web searches or informational queries to BROWSER — those should go to CHAT (CHAT has web search tools for fetching information from the internet).
            - Only route to BROWSER when the user explicitly wants to interact with a specific website, login to a website, perform browser automation, or view webpage content interactively.
            - CRITICAL — CHAT→BROWSER content passing: When planning a CHAT task followed by a BROWSER task (e.g. user says "写文章然后发到语雀"), the BROWSER task MUST use {{LAST_CHAT_TEXT}} as placeholder for the CHAT output. NEVER embed actual content in the BROWSER instruction — it hasn't been generated yet. Example: BROWSER instruction should be "在语雀创建文档，标题为'XXX'，将 {{LAST_CHAT_TEXT}} 的内容写入正文并发布" — NOT "...将[full article text]写入..."

            Resume / PAUSED mechanism (CRITICAL):
            - When the user message starts with "=== 上一轮任务状态（可恢复） ===", a previous turn was PAUSED and is now resuming.
            - The resume block lists:
              * Completed tasks marked "状态: SUCCESS" — these are already done, do NOT re-plan them
              * An interrupt point marked "--- 中断点 ---" showing which agent paused and what message was shown to the user
            - After the "【用户消息】" marker is the user's actual reply text.
            - You MUST:
              1. Skip ALL completed tasks — do not create tasks for them again.
              2. Read the interrupt point to understand what the user was asked.
              3. Plan new tasks based on the user's reply to continue from where execution left off.
            - Agents may autonomously return PAUSED when they need human interaction (e.g. login, car type selection, confirmation). You do NOT plan for PAUSED — you only see its effects during resume.

            Output schemas:
            {"status":"needs_clarification","reasoning":"...","question":"..."}
            {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|TRAVEL|BROWSER|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
            {"status":"completed","reasoning":"...","final_reply":"..."}

            Note: Supported statuses are needs_clarification, execute, completed. Agents may return PAUSED at runtime — this is handled automatically, you do not output it.
            """;

    private static final String REFLECT_SYSTEM_PROMPT = """
            You are reviewing completed subtask results for a multi-agent assistant.
            The prompt includes the ORIGINAL USER REQUEST first, followed by the subtask execution records.
            Return JSON only, with no markdown and no extra text.

            Available agent units:
            %s

            Decide one of the following:
            - completed: the current results are enough to answer the user
            - execute: more tasks are required

            Rules:
            - Compare the execution results against the original user request. Check whether EVERY part of the request has been addressed.
            - If the task results already satisfy ALL parts of the request, return completed.
            - If another step is needed, return execute with the next task list.
            - Prefer using the actual CHAT output as final_reply when it already answers the user.
            - If image or speech generation already succeeded, do not ask to regenerate unless there is a clear failure.
            - For new SPEECH_GEN tasks, follow the same voice selection rules as in planning.
            - For SPEECH_GEN tasks: instruction must be ONLY raw text to speak, no directions or markup.
              * If the text to speak depends on a prior CHAT task's output, use the placeholder {{LAST_CHAT_TEXT}}. The system will automatically replace it with the actual CHAT output text.
            - If the user asked for a file and the CHAT output contains [FILE:...]...[/FILE] markers, the file was already generated successfully — do not route again.
            - DiDi Taxi rule: TRAVEL now uses PAUSED for car selection (you won't see these in reflect). If you do see a TRAVEL task that returned SUCCESS with car type options (without PAUSED), return completed to let the user choose — never auto-create an order.
            - BROWSER task completion verification: When a BROWSER task is marked SUCCESS, read the output carefully. If the instruction asked for multi-step actions (e.g. open page, write document, fill form, submit, publish) but the output only confirms a small prerequisite step (e.g. "login ok", "page loaded"), the task is INCOMPLETE. Return execute and ask BROWSER to continue from where it left off. Only mark completed when the BROWSER output confirms the final goal (document published, form submitted, content saved).
            - You may include an optional "parameters" object on each task.
            - CRITICAL: When a BROWSER task instruction needs to reference content from a previous CHAT task (e.g. article text, report content, generated message), DO NOT copy the full content into the instruction. Use the placeholder {{LAST_CHAT_TEXT}} instead. The system will automatically replace it with the actual CHAT output. This keeps the JSON compact and prevents parsing errors.
            - Example bad instruction: "...paste this entire 5000-word article: [全文嵌入]" → this will break
            - Example good instruction: "将 {{LAST_CHAT_TEXT}} 的内容粘贴到文档编辑器中"
            - CRITICAL — CHAT failure handling: If a CHAT task's output is an error message (contains "抱歉"、"失败"、"超时"、"暂不可用"、"未生成"), do NOT use that output as content for a BROWSER task. Instead, create a new CHAT task to retry with a simplified instruction. Only route to BROWSER when CHAT has produced real content.
            - PAUSED tasks: If a subtask result shows status=PAUSED, the agent needs human input and the system has already returned the pause message to the user. You should NOT see PAUSED tasks in reflect (they are intercepted before reflect is called). If you do see one, return completed — the user must reply before more work can be done.

            Output schemas:
            {"status":"completed","reasoning":"...","final_reply":"..."}
            {"status":"execute","reasoning":"...","tasks":[{"agent_type":"CHAT|TRAVEL|BROWSER|IMAGE_GEN|SPEECH_GEN","instruction":"...","parameters":{"key":"value"}}]}
            """;

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final String capabilitiesPrompt;
    private final AgentRegistry agentRegistry;

    public OrchestratorAgentImpl(AgentProperties props, AgentRegistry registry) {
        this.agentRegistry = registry;
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
            messages.add(Map.of("role", msg.getRole(), "content", String.valueOf(msg.getContent())));
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
    public OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
        try {
            return doReflect(scratchpad, originalRequest);
        } catch (Exception e) {
            log.warn("Orchestrator reflect() failed, treating as completed. error={}", e.getMessage());
            return buildCompletedResult(scratchpad);
        }
    }

    private OrchestrationResult doReflect(TaskScratchpad scratchpad, UserRequest originalRequest) {
        StringBuilder reflectContent = new StringBuilder();
        reflectContent.append("=== ORIGINAL USER REQUEST ===\n");
        reflectContent.append(originalRequest.text() != null ? originalRequest.text() : "");
        if (!originalRequest.imageBase64Urls().isEmpty()) {
            reflectContent.append("\n[user attached images: ").append(originalRequest.imageBase64Urls().size()).append("]");
        }
        if (originalRequest.rememberedImageSummary() != null && !originalRequest.rememberedImageSummary().isBlank()) {
            reflectContent.append("\n[remembered image summary] ").append(originalRequest.rememberedImageSummary());
        }
        reflectContent.append("\n\n");
        reflectContent.append(scratchpad.toReflectPrompt());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", String.format(REFLECT_SYSTEM_PROMPT, capabilitiesPrompt)));
        messages.add(Map.of("role", "user", "content", reflectContent.toString()));

        String response = callModel(messages);
        OrchestrationResult result = parseReflectResponse(response, scratchpad);
        // Replace {{LAST_CHAT_TEXT}} placeholder in any task instructions
        String lastChatText = scratchpad.lastSuccessfulChatText();
        if (lastChatText != null && !lastChatText.isBlank()
                && result.status() == OrchestrationResult.Status.EXECUTE) {
            // Skip substitution if the CHAT output looks like an error message
            if (MessageRouter.isLikelyErrorText(lastChatText)) {
                log.warn("orchestrator: refusing to use error-like CHAT text as {{LAST_CHAT_TEXT}}: {}",
                        lastChatText.length() > 100 ? lastChatText.substring(0, 100) + "..." : lastChatText);
            } else {
                List<AgentTask> replacedTasks = new ArrayList<>();
                boolean needsReplace = false;
                for (AgentTask task : result.tasks()) {
                    if (task.instruction().contains("{{LAST_CHAT_TEXT}}")) {
                        replacedTasks.add(new AgentTask(task.agentType(),
                                task.instruction().replace("{{LAST_CHAT_TEXT}}", lastChatText),
                                task.parameters()));
                        needsReplace = true;
                    } else {
                        replacedTasks.add(task);
                    }
                }
                if (needsReplace) {
                    result = OrchestrationResult.builder()
                            .status(OrchestrationResult.Status.EXECUTE)
                            .reasoning(result.reasoning())
                            .tasks(replacedTasks)
                            .scratchpad(scratchpad)
                            .build();
                }
            }
        }
        return result;
    }

    private String callModel(List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.0);
        body.put("max_tokens", 4096);

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
        // If the request is clearly browser-related and BROWSER is available, route there
        if (agentRegistry.contains("BROWSER") && text != null && isBrowserIntent(text)) {
            return OrchestrationResult.builder()
                    .status(OrchestrationResult.Status.EXECUTE)
                    .reasoning("fallback: orchestrator plan failed, detected browser intent, routing to BROWSER")
                    .tasks(List.of(new AgentTask("BROWSER", text, Map.of())))
                    .build();
        }
        return OrchestrationResult.builder()
                .status(OrchestrationResult.Status.EXECUTE)
                .reasoning("fallback: orchestrator model unavailable, default to chat")
                .tasks(List.of(new AgentTask("CHAT", text != null ? text : "", Map.of())))
                .build();
    }

    /** Detects if user message is about browser/webpage interaction. */
    static boolean isBrowserIntent(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("登录") || t.contains("打开") || t.contains("网站")
                || t.contains("网页") || t.contains("浏览器") || t.contains("yuque")
                || t.contains("语雀") || t.contains("发布") || t.contains("写入")
                || t.contains("填写") || t.contains("截图") || t.contains("点一下")
                || t.contains("点击") || t.contains("填表") || t.contains("粘贴")
                || t.contains("复制进去") || t.contains("保存到");
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
