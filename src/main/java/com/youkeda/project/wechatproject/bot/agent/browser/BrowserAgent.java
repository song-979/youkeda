package com.youkeda.project.wechatproject.bot.agent.browser;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
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
import java.util.function.Consumer;

/**
 * Handles browser automation through Spring AI tool-calling with only browser tools.
 * Supports progress callbacks for long-running operations.
 */
public class BrowserAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgent.class);
    private static final long TOOL_LOOP_TIMEOUT_SECONDS = 600;
    private final ChatClient toolChatClient;
    private final String skillsSummary;

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory) {
        this(browserToolChatClientFactory, "");
    }

    public BrowserAgent(ToolChatClientFactory browserToolChatClientFactory, String skillsSummary) {
        this.toolChatClient = browserToolChatClientFactory != null
                ? browserToolChatClientFactory.create() : null;
        this.skillsSummary = skillsSummary != null ? skillsSummary : "";
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
                "text"
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

        try {
            onProgress.accept("正在启动浏览器...");

            var executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() ->
                    toolChatClient.prompt()
                            .system("""
                                你是浏览器自动化助手。请严格遵循以下原则：""" + (skillsSummary.isEmpty() ? "" : ("\n\n" + skillsSummary + "\n\n---\n\n")) + """

                                0. 【PAUSED 中断机制——登录等需要用户手动操作的场景】
                                   当你需要用户手动操作（如扫码登录、输入验证码）时：
                                   a. 调用 browser_navigate 或 browser_request_human_login 打开登录页面
                                   b. 调用 browser_screenshot 截取页面（二维码等），截图会自动暂存
                                   c. 调用 send_message_to_user(text="请扫描二维码登录，完成后回复'已登录'", includeScreenshot=true)
                                      将截图发送给用户
                                   d. 工具返回后，输出 __PAUSED__: 标记（消息文本会显示为图片说明）
                                   e. 不要再继续执行后续任何步骤！系统会自动保存当前进度，等用户回复后恢复执行。
                                   注意：__PAUSED__: 必须是回复的第一行开头。
                                   如果不需要截图（如纯文字确认），可跳过步骤b-c，直接输出 __PAUSED__:提示消息。

                                0-1. 【完成全部任务——最高优先级】
                                   - 指令可能包含多个步骤，必须完成全部步骤，不仅仅是第一步。
                                   - 任务完成的标志是：用户要求的最终目标已达成（如文档已发布、表单已提交）。

                                1. 【减少截图】只在以下情况使用 browser_snapshot：
                                   - 刚打开页面后（获取页面结构）
                                   - 登录验证时
                                   - 表单提交后确认结果
                                   - 任务完成时确认最终状态
                                   不要每次点击/填写后都截图。

                                2. 【连续操作】点击、填写等操作可以直接连续执行3-5步后再截图确认。

                                3. 【快速失败】如果同一操作失败2次，直接报告错误而不是反复重试。不要对同一元素反复尝试不同uid。

                                4. 【控制步数】整个任务尽量在20步工具调用以内完成。

                                5. 【富文本编辑器——最重要的规则】
                                   语雀、微信公众号、飞书等平台的正文编辑器是contenteditable富文本编辑器，不是INPUT/TEXTAREA。
                                   browser_fill 和 browser_type_text 对这类编辑器通常无效或极慢。
                                   填写正文请使用以下可靠方案：

                                   **方案A（首选——JS注入，快速可靠）：**
                                   a. 用 browser_evaluate_script 查找内容可编辑元素：
                                      document.querySelector('[contenteditable="true"]') 或 document.querySelector('.ProseMirror') 或 document.querySelector('[role="textbox"]')
                                   b. 确认找到元素后，用 browser_click 点击编辑器区域使其获得焦点
                                   c. 用 browser_evaluate_script 写入内容（将内容中的换行替换为<br>，引号转义）：
                                      const el = document.querySelector('[contenteditable="true"]') || document.querySelector('.ProseMirror');
                                      el.focus();
                                      el.innerHTML = '第一段<br><br>第二段<br><br>第三段';
                                   d. 写入后触发input事件通知编辑器框架：
                                      el.dispatchEvent(new Event('input', {bubbles: true}));
                                      el.dispatchEvent(new Event('change', {bubbles: true}));

                                   **方案B（备选——逐字输入，仅适用于500字以内的短内容）：**
                                   a. browser_click 点击编辑器区域
                                   b. browser_type_text 逐字输入

                                   注意：
                                   - 如果你在snapshot中找不到明显的正文编辑器uid，不要反复尝试不同的uid！直接用方案A。
                                   - 不要对富文本编辑器使用browser_fill，它只适用于普通INPUT/TEXTAREA元素。
                                   - 长篇内容(>500字)直接用方案A，方案B会超时。
                                """)
                            .user(task.instruction())
                            .call()
                            .content());

            String response;
            try {
                response = future.get(TOOL_LOOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

            // Detect PAUSED signal from LLM output
            if (response != null && response.startsWith("__PAUSED__:")) {
                String messageToUser = response.substring("__PAUSED__:".length()).trim();

                // Check for send_message_to_user output first
                UserMessageTool.PendingUserMessage pending = UserMessageTool.drain();
                String effectiveMessage = (pending != null && !pending.text().isBlank())
                        ? pending.text() : messageToUser;

                // Collect images: from send_message_to_user + remaining screenshots
                List<byte[]> allImages = new ArrayList<>();
                if (pending != null) {
                    allImages.addAll(pending.images());
                }
                allImages.addAll(BrowserTools.drainScreenshots());

                List<ModelReply.ImagePayload> imagePayloads = new ArrayList<>();
                for (int i = 0; i < allImages.size(); i++) {
                    imagePayloads.add(new ModelReply.ImagePayload(allImages.get(i),
                            "screenshot_" + (i + 1) + ".png"));
                }

                log.info("BrowserAgent PAUSED: message={}, images={}", effectiveMessage, imagePayloads.size());
                return AgentResult.paused(task.taskId(), effectiveMessage, Map.of(), imagePayloads);
            }

            return AgentResult.success(task.taskId(), response, response);
        } catch (RuntimeException e) {
            log.warn("BrowserAgent tool loop failed: {}", e.getMessage());
            onProgress.accept("浏览器操作失败: " + e.getMessage());
            return AgentResult.failed(task.taskId(),
                    "浏览器操作失败：" + e.getMessage());
        }
    }
}
