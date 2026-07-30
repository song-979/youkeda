package com.youkeda.project.wechatproject.bot.tool.chat;

import com.youkeda.project.wechatproject.bot.tool.ToolService;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * General-purpose tool that lets the LLM queue messages and images to send
 * back to the user. Works with the PAUSED mechanism to pause execution
 * after sending, then resume when the user replies.
 */
public class UserMessageTool implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(UserMessageTool.class);

    @Override
    public String category() {
        return "information";
    }

    private static final ThreadLocal<String> PENDING_MESSAGE = new ThreadLocal<>();
    private static final ThreadLocal<List<byte[]>> PENDING_IMAGES =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Drain the queued user message and images for this thread, then clear.
     * Returns null text if nothing was queued.
     */
    public static PendingUserMessage drain() {
        String text = PENDING_MESSAGE.get();
        List<byte[]> images = new ArrayList<>(PENDING_IMAGES.get());
        PENDING_MESSAGE.remove();
        PENDING_IMAGES.get().clear();
        if (text == null && images.isEmpty()) {
            return null;
        }
        return new PendingUserMessage(
                text != null ? text : "",
                images.isEmpty() ? List.of() : List.copyOf(images));
    }

    /** Clear any queued state for this thread (called at end of request). */
    public static void clear() {
        PENDING_MESSAGE.remove();
        PENDING_IMAGES.get().clear();
    }

    public record PendingUserMessage(String text, List<byte[]> images) {}

    @Tool(name = "send_message_to_user",
          description = "向用户发送消息和（可选）截图。当需要用户查看信息、确认操作、或扫码登录时使用。"
                      + "调用后应输出 __PAUSED__: 标记暂停等待用户回复。"
                      + "如果用户需要看到页面内容（如二维码），先调用 browser_screenshot 再调用本工具并设置 includeScreenshot=true。")
    public String sendMessageToUser(
            @ToolParam(description = "要发送给用户的消息内容") String text,
            @ToolParam(description = "是否附带最近一次 browser_screenshot 的截图（用于登录二维码等场景）")
            boolean includeScreenshot) {
        PENDING_MESSAGE.set(text);
        if (includeScreenshot) {
            try {
                List<byte[]> screenshots = BrowserTools.drainScreenshots();
                if (!screenshots.isEmpty()) {
                    PENDING_IMAGES.get().addAll(screenshots);
                    log.info("send_message_to_user: queued text + {} screenshot(s)", screenshots.size());
                } else {
                    log.info("send_message_to_user: queued text, but no screenshots available");
                }
            } catch (Exception e) {
                log.warn("send_message_to_user: failed to drain screenshots: {}", e.getMessage());
            }
        } else {
            log.info("send_message_to_user: queued text only");
        }
        return "消息已排队待发送"
                + (includeScreenshot ? "（含截图）" : "")
                + "。请输出 __PAUSED__: 标记暂停等待用户回复。";
    }
}
