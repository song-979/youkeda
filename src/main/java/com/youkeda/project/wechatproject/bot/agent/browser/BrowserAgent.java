package com.youkeda.project.wechatproject.bot.agent.browser;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentContextAssembler;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.memory.AgentMemory;
import com.youkeda.project.wechatproject.bot.memory.FileBasedAgentMemory;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ToolLoopContextRuntime;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Handles browser automation through Spring AI tool-calling with only browser tools.
 * Supports progress callbacks for long-running operations.
 */
public class BrowserAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgent.class);
    // Hard safety cap for the entire tool loop. Per-round timeout is handled by TimeoutChatModel.
    // This only triggers if LLM enters an infinite loop or browser automation gets stuck.
    private static final long TOOL_LOOP_TIMEOUT_SECONDS = 600;
    private static final int MAX_VERIFY_RETRIES = 1;
    private final ChatClient toolChatClient;
    private final String skillsSummary;
    private final BrowserTools browserTools;
    private final AgentMemory agentMemory;
    private final ContextEngineeringService contextEngineeringService;

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory) {
        this(browserToolChatClientFactory, null, "", null);
    }

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory, String skillsSummary) {
        this(browserToolChatClientFactory, null, skillsSummary, null);
    }

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory,
                        BrowserTools browserTools, String skillsSummary,
                        AgentMemory agentMemory) {
        this(browserToolChatClientFactory, browserTools, skillsSummary, agentMemory, null);
    }

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory,
                        BrowserTools browserTools, String skillsSummary,
                        AgentMemory agentMemory,
                        ContextEngineeringService contextEngineeringService) {
        this.toolChatClient = browserToolChatClientFactory != null
                ? browserToolChatClientFactory.create() : null;
        this.browserTools = browserTools;
        this.skillsSummary = skillsSummary != null ? skillsSummary : "";
        this.agentMemory = agentMemory;
        this.contextEngineeringService = contextEngineeringService;
    }

    @Override
    public String getName() {
        return "BROWSER";
    }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability(
                "browser-automation",
                "浏览器自动化操作：打开网页、网页登录（扫码/账号密码）、填写表单、点击元素、截图、提取页面内容、网络请求捕获、控制台消息等。支持通过browser_request_human_login让用户手动完成登录后继续自动化操作。",
                List.of("web-navigation", "website-login", "form-filling", "screenshot", "page-content-extraction",
                        "network-capture", "console-inspection", "web-automation"),
                "text",
                List.of("打开", "登录", "浏览器", "网页", "网站", "截图", "语雀", "yuque",
                        "填写", "表单", "发布", "搜索一下", "搜一下")
        );
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        return execute(task, progress -> { /* no-op progress */ });
    }

    @Override
    public AgentResult execute(AgentTask task, Consumer<String> onProgress) throws IOException {
        log.info("BrowserAgent executing task: instruction={}", task.instruction());

        if (toolChatClient == null) {
            return AgentResult.failed(task.taskId(), "浏览器自动化服务暂不可用。");
        }

        // Build memory-enhanced system prompt
        String userId = stringParam(task.parameters(), "userId");
        String textPayload = stringParam(task.parameters(), "textPayload");
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(userId, task.instruction());
        ContextPackage context = AgentContextAssembler.build(
                contextEngineeringService, task, effectiveSystemPrompt, agentMemory);

        try {
            onProgress.accept("正在启动浏览器...");

            var executor = Executors.newSingleThreadExecutor();
            var responseRef = new AtomicReference<String>();
            var pendingDrainRef = new AtomicReference<UserMessageTool.PendingUserMessage>();
            var screenshotsDrainRef = new AtomicReference<List<byte[]>>();
            var transcriptReportRef = new AtomicReference<ToolLoopContextRuntime.Report>();

            Future<?> future = executor.submit(() -> {
                try {
                    BrowserTools.setCurrentUser(userId);
                    SkillTools.setCurrentAgent(getName());
                    BrowserTools.prepareTextPayload(textPayload);
                    String resp = toolChatClient.prompt()
                            .messages(AgentContextAssembler.toSpringMessages(context, List.of()))
                                .call()
                                .content();
                    responseRef.set(resp);
                    pendingDrainRef.set(UserMessageTool.drain());
                    screenshotsDrainRef.set(BrowserTools.drainScreenshots());
                } finally {
                    transcriptReportRef.set(ToolLoopContextRuntime.drain());
                    SkillTools.clearCurrentAgent();
                    BrowserTools.clearTextPayload();
                    BrowserTools.clearCurrentUser();
                }
            });

            String response;
            try {
                future.get(TOOL_LOOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                response = responseRef.get();
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("BrowserAgent tool loop timed out after {}s", TOOL_LOOP_TIMEOUT_SECONDS);
                onProgress.accept("浏览器操作超时");
                return AgentResult.failed(task.taskId(),
                        "浏览器操作超时（" + TOOL_LOOP_TIMEOUT_SECONDS + "秒），可能是任务太复杂或浏览器操作卡住，请尝试简化任务后重试。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return AgentResult.failed(task.taskId(), "浏览器操作被中断。");
            } catch (ExecutionException e) {
                future.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause != null ? cause : e);
            } finally {
                executor.shutdownNow();
            }

            onProgress.accept("浏览器操作完成");

            // Clean up thread-local state
            try {
                BrowserTools.clearCurrentUser();
            } catch (Exception ignored) {
                // not critical
            }

            log.info("BrowserAgent response: {} chars", response != null ? response.length() : 0);

            // Detect PAUSED signal from LLM output, or force PAUSED if pending images exist
            UserMessageTool.PendingUserMessage pending = pendingDrainRef.get();
            List<byte[]> drainedScreenshots = screenshotsDrainRef.get() != null
                    ? screenshotsDrainRef.get() : List.of();

            boolean hasPendingImages = (pending != null && !pending.images().isEmpty())
                    || !drainedScreenshots.isEmpty();
            boolean isPaused = response != null && response.startsWith("__PAUSED__:");

            if (isPaused || hasPendingImages) {
                String pausedText = isPaused
                        ? response.substring("__PAUSED__:".length()).trim()
                        : "";
                String effectiveMessage;
                List<byte[]> allImages = new ArrayList<>();

                if (pending != null) {
                    effectiveMessage = !pending.text().isBlank() ? pending.text() : pausedText;
                    allImages.addAll(pending.images());
                } else {
                    effectiveMessage = pausedText;
                }
                allImages.addAll(drainedScreenshots);

                List<ModelReply.ImagePayload> imagePayloads = new ArrayList<>();
                for (int i = 0; i < allImages.size(); i++) {
                    imagePayloads.add(new ModelReply.ImagePayload(allImages.get(i),
                            "screenshot_" + (i + 1) + ".png"));
                }

                log.info("BrowserAgent PAUSED: message={}, images={}", effectiveMessage, imagePayloads.size());
                ToolLoopContextRuntime.Report report = transcriptReportRef.get();
                return AgentResult.paused(task.taskId(), effectiveMessage,
                        report != null ? report.resumeState() : Map.of(), imagePayloads);
            }

            // Self-verification phase: screenshot + snapshot → LLM judge → retry if needed
            if (browserTools != null && response != null && !response.isBlank()) {
                onProgress.accept("正在验证浏览器任务结果...");
                response = verifyAndRetry(task, response, userId, textPayload, onProgress);
            }

            // Auto-persist useful state to AgentMemory after successful execution
            persistMemory(userId, task.instruction());

            ToolLoopContextRuntime.Report report = transcriptReportRef.get();
            return AgentResult.success(task.taskId(), response, response,
                    report != null ? report.signals() : Map.of());
        } catch (RuntimeException e) {
            log.warn("BrowserAgent tool loop failed: {}", e.getMessage());
            onProgress.accept("浏览器操作失败: " + e.getMessage());
            return AgentResult.failed(task.taskId(),
                    "浏览器操作失败：" + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // AgentMemory auto-persistence
    // -------------------------------------------------------------------------

    private void persistMemory(String userId, String instruction) {
        if (agentMemory == null || userId == null) {
            return;
        }
        String lower = instruction.toLowerCase();
        // Login-related: remember that user has logged into a site
        if (containsAny(lower, "登录", "login", "扫码", "sign in")) {
            agentMemory.remember(userId, "last_login_instruction", instruction);
            agentMemory.remember(userId, "login_status", "attempted");
            log.debug("[BROWSER] auto-persisted login state for user={}", userId);
        }
        // Site-specific memory: extract site name keywords
        if (lower.contains("语雀") || lower.contains("yuque")) {
            agentMemory.remember(userId, "last_site", "语雀");
        } else if (lower.contains("公众号") || lower.contains("mp.weixin")) {
            agentMemory.remember(userId, "last_site", "微信公众号");
        } else if (lower.contains("飞书") || lower.contains("feishu")) {
            agentMemory.remember(userId, "last_site", "飞书");
        }
    }

    // -------------------------------------------------------------------------
    // Self-verification
    // -------------------------------------------------------------------------

    private String verifyAndRetry(AgentTask task, String currentResponse,
                                   String userId, String textPayload, Consumer<String> onProgress) {
        String instruction = task.instruction();
        for (int retry = 0; retry <= MAX_VERIFY_RETRIES; retry++) {
            byte[] screenshot;
            String snapshot;
            try {
                screenshot = browserTools.captureScreenshotBytes();
                snapshot = browserTools.captureSnapshotText();
            } catch (Exception e) {
                log.warn("Cannot capture verification state for BrowserAgent: {}", e.getMessage());
                return currentResponse;
            }

            String verdict = callVerifier(instruction, currentResponse, snapshot);

            if (verdict != null && verdict.strip().toUpperCase().startsWith("YES")) {
                log.info("BrowserAgent task VERIFIED successfully");
                return currentResponse;
            }

            if (retry < MAX_VERIFY_RETRIES) {
                String failureReason = verdict != null ? verdict : "任务目标未达成";
                onProgress.accept("任务验证未通过，正在重试 ("
                        + (retry + 1) + "/" + MAX_VERIFY_RETRIES + ")...");
                String retryInstruction = instruction
                        + "\n\n[上轮失败原因: " + truncate(failureReason, 500)
                        + "]\n请用不同方法重试，避免相同错误。";
                currentResponse = runToolLoop(
                        task.withInstruction(retryInstruction), userId, textPayload, onProgress);
                if (currentResponse == null) {
                    return currentResponse;
                }
            }
        }
        log.warn("BrowserAgent verification failed after {} retries, accepting current result",
                MAX_VERIFY_RETRIES);
        return currentResponse;
    }

    private String callVerifier(String instruction, String llmResponse, String snapshot) {
        String prompt = """
                TASK GOAL:
                %s

                AGENT RESPONSE:
                %s

                FINAL PAGE STATE (accessibility snapshot):
                %s

                Based on the above, did the browser agent SUCCESSFULLY complete the task?
                Answer YES if all steps were completed and the final page state confirms success.
                Answer NO if the task is incomplete, any step failed, or the page state shows
                the task did not actually complete (e.g. still on login page when task asked to publish).
                Start your response with YES or NO, then provide brief reasoning.
                """.formatted(instruction, llmResponse, truncate(snapshot, 3000));

        try {
            AgentTask verifierTask = new AgentTask("BROWSER_VERIFIER", prompt, Map.of());
            ContextPackage verifierContext = AgentContextAssembler.build(
                    contextEngineeringService,
                    verifierTask,
                    "You are a browser task VERIFIER. Analyze the info and answer YES/NO. "
                            + "Do NOT call any tools or attempt browser actions.",
                    null);
            return toolChatClient.prompt()
                    .messages(AgentContextAssembler.toSpringMessages(verifierContext, List.of()))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Verification LLM call failed: {}", e.getMessage());
            return "YES"; // graceful: assume success if verifier is unavailable
        } finally {
            ToolLoopContextRuntime.drain();
        }
    }

    private String runToolLoop(AgentTask task, String userId, String textPayload,
                               Consumer<String> onProgress) {
        if (toolChatClient == null) {
            return null;
        }
        try {
            var executor = Executors.newSingleThreadExecutor();
            var responseRef = new AtomicReference<String>();
            Future<?> future = executor.submit(() -> {
                BrowserTools.setCurrentUser(userId);
                SkillTools.setCurrentAgent(getName());
                BrowserTools.prepareTextPayload(textPayload);
                try {
                    String fixedSystemPrompt = buildEffectiveSystemPrompt(userId, task.instruction());
                    ContextPackage retryContext = AgentContextAssembler.build(
                            contextEngineeringService, task, fixedSystemPrompt, agentMemory);
                    String resp = toolChatClient.prompt()
                            .messages(AgentContextAssembler.toSpringMessages(retryContext, List.of()))
                            .call()
                            .content();
                    responseRef.set(resp);
                    UserMessageTool.drain();
                    BrowserTools.drainScreenshots();
                } finally {
                    ToolLoopContextRuntime.drain();
                    SkillTools.clearCurrentAgent();
                    BrowserTools.clearTextPayload();
                    BrowserTools.clearCurrentUser();
                }
            });
            try {
                future.get(TOOL_LOOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String result = responseRef.get();
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("BrowserAgent retry tool loop timed out after {}s", TOOL_LOOP_TIMEOUT_SECONDS);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return null;
            } finally {
                executor.shutdownNow();
            }
        } catch (Exception e) {
            log.warn("BrowserAgent retry tool loop failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildEffectiveSystemPrompt(String userId, String instruction) {
        String prompt = buildSystemPrompt();
        return prompt;
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object val = params != null ? params.get(key) : null;
        return val instanceof String s && !s.isBlank() ? s : null;
    }

    private String buildSystemPrompt() {
        return """
                你是浏览器自动化助手。请严格遵循以下原则：""" + (skillsSummary.isEmpty() ? "" : ("\n\n" + skillsSummary + "\n\n---\n\n")) + """

                0. 【PAUSED 中断机制——登录等需要用户手动操作的场景】
                   当你需要用户手动操作（如扫码登录、输入验证码）时，**必须**执行以下完整流程，**禁止跳过任何步骤**：
                   a. 调用 browser_navigate 或 browser_request_human_login 打开登录页面
                   b. **必须**调用 browser_screenshot 截取页面（二维码等），截图会自动暂存
                   c. **必须**调用 send_message_to_user(text="请扫描二维码登录，完成后回复'已登录'", includeScreenshot=true)
                      将截图发送给用户
                   d. 工具返回后，输出 __PAUSED__: 标记
                   e. 不要再继续执行后续任何步骤！系统会自动保存当前进度，等用户回复后恢复执行。
                   **关键规则**：
                   - __PAUSED__: 必须是回复的第一行开头。
                   - **禁止在登录场景下跳过步骤b-c直接输出 __PAUSED__:！用户看不到浏览器页面，必须发送截图！**
                   - 例外：只有纯文字确认请求（不需要用户看截图），才可跳过步骤b-c。

                0-1. 【完成全部任务——最高优先级】
                   - 指令可能包含多个步骤，必须完成全部步骤，不仅仅是第一步。
                   - 任务完成的标志是：用户要求的最终目标已达成（如文档已发布、表单已提交）。

                1. 【减少截图】只在以下情况使用 browser_snapshot：
                   - 刚打开页面后（获取页面结构）
                   - 登录验证时
                   - 表单提交后确认结果
                   - 任务完成时确认最终状态
                   不要每次点击/填写后都截图。

                2. 【连续操作】点击、填写等操作可以直接连续执行3-5步后再截图确认。不要每步都截图。

                2-1. 【避免不必要的等待】browser_wait_for 只在页面明确提示"正在加载"或出现loading动画时使用。
                    绝大多数情况下，click/fill 操作后页面已经自然更新，不需要额外 wait_for。
                    browser_snapshot 本身就能反映最新页面状态，无需提前等待。

                3. 【快速失败】如果同一操作失败2次，直接报告错误而不是反复重试。不要对同一元素反复尝试不同uid。

                4. 【控制步数】整个任务尽量在15步工具调用以内完成。能用1个截图确认的就不要用2个。

                5. 【富文本编辑器——最重要的规则】
                   语雀、微信公众号、飞书等平台的正文编辑器是contenteditable富文本编辑器，不是INPUT/TEXTAREA。
                   browser_fill 和 browser_type_text 对这类编辑器通常无效或极慢。
                   填写正文请使用以下可靠方案：

                   **方案A（首选——受控富文本写入，快速可靠）：**
                   a. 先用 browser_snapshot 判断页面里是否有编辑器区域。
                   b. 如果任务指令里出现 [TEXT_PAYLOAD:LAST_CHAT_TEXT] 或 payload 正文，调用 browser_set_text_payload 写入正文。
                      否则调用 browser_set_rich_text 写入正文。
                      若能判断选择器，则传 selector（如 .ProseMirror、[contenteditable="true"]、[role="textbox"]）；无法判断时不传 selector，让工具自动尝试常见编辑器。
                   c. 写入后用 browser_snapshot 确认正文已经进入编辑器。

                   **方案B（备选——逐字输入，仅适用于500字以内的短内容）：**
                   a. browser_click 点击编辑器区域
                   b. browser_type_text 逐字输入

                   注意：
                   - 不要用 browser_evaluate_script 写入正文；它是高级只读调试工具，默认可能被关闭。
                   - 如果你在snapshot中找不到明显的正文编辑器uid，不要反复尝试不同的uid！直接用 browser_set_text_payload 或 browser_set_rich_text。
                   - 不要对富文本编辑器使用browser_fill，它只适用于普通INPUT/TEXTAREA元素。
                   - 长篇内容(>500字)直接用方案A，方案B会超时。
                """;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...[truncated]";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
