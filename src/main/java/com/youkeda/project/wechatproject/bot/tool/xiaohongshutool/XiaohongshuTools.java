package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.project.wechatproject.bot.tool.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

public class XiaohongshuTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(XiaohongshuTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<PublishCaptureState> PUBLISH_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<AccountActionCaptureState> ACCOUNT_ACTION_CAPTURE = new ThreadLocal<>();

    private final XiaohongshuMcpClient client;
    private final XiaohongshuProperties properties;

    public XiaohongshuTools(XiaohongshuMcpClient client, XiaohongshuProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public static PublishInvocationCapture capturePublishInvocations() {
        PublishCaptureState previous = PUBLISH_CAPTURE.get();
        PublishCaptureState current = new PublishCaptureState();
        PUBLISH_CAPTURE.set(current);
        return new PublishInvocationCapture(previous, current);
    }

    public static AccountActionInvocationCapture captureAccountActionInvocations() {
        AccountActionCaptureState previous = ACCOUNT_ACTION_CAPTURE.get();
        AccountActionCaptureState current = new AccountActionCaptureState();
        ACCOUNT_ACTION_CAPTURE.set(current);
        return new AccountActionInvocationCapture(previous, current);
    }

    @Override
    public String category() {
        return "xiaohongshu";
    }

    @Tool(name = "xiaohongshu_check_login_status",
          description = "Check the current Xiaohongshu MCP login account.")
    public String checkLoginStatus() {
        try {
            JsonNode result = client.callTool(properties.getLoginStatusToolName(), OBJECT_MAPPER.createObjectNode());
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);

            StringBuilder sb = new StringBuilder("\u3010\u5c0f\u7ea2\u4e66\u3011\u767b\u5f55\u72b6\u6001");
            String loggedIn = firstText(structured, "isLoggedIn", "is_logged_in", "loggedIn", "logged_in", "login");
            if (!loggedIn.isBlank()) {
                sb.append("\n\u72b6\u6001\uff1a").append(isTruthy(loggedIn) ? "\u5df2\u767b\u5f55" : "\u672a\u767b\u5f55");
            }
            appendField(sb, "\u8d26\u53f7", firstText(structured, "nickname", "nickName", "name", "userName", "user_name"));
            appendField(sb, "\u7528\u6237ID", firstText(structured, "userId", "user_id", "id"));
            appendField(sb, "\u4e3b\u9875", firstText(structured, "profileUrl", "profile_url", "url", "homeUrl", "home_url"));
            if (text != null && !text.isBlank()) {
                sb.append("\n\n\u539f\u59cb\u8fd4\u56de\uff1a\n").append(text.trim());
            }
            return sb.toString().trim();
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu check login status failed", e);
            return "\u68c0\u67e5\u5c0f\u7ea2\u4e66\u767b\u5f55\u72b6\u6001\u5931\u8d25\uff1a" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu check login status unexpected error", e);
            return "\u68c0\u67e5\u5c0f\u7ea2\u4e66\u767b\u5f55\u72b6\u6001\u5f02\u5e38\uff1a" + e.getMessage();
        }
    }

    @Tool(name = "xiaohongshu_request_login_qrcode",
          description = "Request Xiaohongshu login QR code from MCP.")
    public String requestLoginQrcode() {
        try {
            JsonNode result = client.callTool(properties.getLoginQrcodeToolName(), OBJECT_MAPPER.createObjectNode());
            String text = client.getTextContent(result);
            List<XiaohongshuMcpClient.McpImageContent> images = client.getImageContents(result);

            StringBuilder sb = new StringBuilder("\u3010\u5c0f\u7ea2\u4e66\u3011\u767b\u5f55\u4e8c\u7ef4\u7801");
            if (text != null && !text.isBlank()) {
                sb.append("\n").append(text.trim());
            } else {
                sb.append("\n\u8bf7\u7528\u5c0f\u7ea2\u4e66 App \u626b\u7801\u767b\u5f55\u3002");
            }
            if (images.isEmpty()) {
                sb.append("\n\n\u672a\u83b7\u53d6\u5230\u4e8c\u7ef4\u7801\u56fe\u7247\u3002");
                return sb.toString().trim();
            }

            XiaohongshuMcpClient.McpImageContent image = images.get(0);
            sb.append("\n\n[XHS_LOGIN_QR:")
                    .append(image.mimeType())
                    .append(";")
                    .append(image.data())
                    .append("]");
            return sb.toString().trim();
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu request login qrcode failed", e);
            return "\u83b7\u53d6\u5c0f\u7ea2\u4e66\u767b\u5f55\u4e8c\u7ef4\u7801\u5931\u8d25\uff1a" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu request login qrcode unexpected error", e);
            return "\u83b7\u53d6\u5c0f\u7ea2\u4e66\u767b\u5f55\u4e8c\u7ef4\u7801\u5f02\u5e38\uff1a" + e.getMessage();
        }
    }

    @Tool(name = "xiaohongshu_publish_image_note",
          description = "Publish Xiaohongshu image-text note. Use only when user explicitly asks to publish.")
    public String publishImageNote(
            @ToolParam(description = "Xiaohongshu note title") String title,
            @ToolParam(description = "Xiaohongshu note content") String content,
            @ToolParam(description = "Image URLs or local paths, separated by comma/newline, or JSON string array") String imageUrls,
            @ToolParam(description = "Tags, separated by comma/newline") String tags,
            @ToolParam(description = "Dry run only; true means MCP is not called") Boolean dryRun) {
        List<String> images = splitList(imageUrls, properties.getMaxImageCount());
        if (isBlank(title) || isBlank(content) || images.isEmpty()) {
            return "\u53d1\u5e03\u5c0f\u7ea2\u4e66\u56fe\u6587\u5931\u8d25\uff1a\u6807\u9898\u3001\u6b63\u6587\u548c\u81f3\u5c11\u4e00\u5f20\u56fe\u7247\u90fd\u4e0d\u80fd\u4e3a\u7a7a\u3002";
        }

        ObjectNode args = OBJECT_MAPPER.createObjectNode();
        args.put("title", title.trim());
        args.put("content", content.trim());
        args.set("images", toArray(images));
        args.set("tags", toArray(normalizeTags(tags)));

        return publish(properties.getPublishImageToolName(), args, "\u56fe\u6587", dryRun);
    }

    @Tool(name = "xiaohongshu_publish_video_note",
          description = "Publish Xiaohongshu video note. Use only when user explicitly asks to publish.")
    public String publishVideoNote(
            @ToolParam(description = "Xiaohongshu video title") String title,
            @ToolParam(description = "Xiaohongshu video content") String content,
            @ToolParam(description = "Video URL or local path") String videoUrl,
            @ToolParam(description = "Optional cover image URL or local path") String coverUrl,
            @ToolParam(description = "Tags, separated by comma/newline") String tags,
            @ToolParam(description = "Dry run only; true means MCP is not called") Boolean dryRun) {
        if (isBlank(title) || isBlank(content) || isBlank(videoUrl)) {
            return "\u53d1\u5e03\u5c0f\u7ea2\u4e66\u89c6\u9891\u5931\u8d25\uff1a\u6807\u9898\u3001\u6b63\u6587\u548c\u89c6\u9891\u5730\u5740\u90fd\u4e0d\u80fd\u4e3a\u7a7a\u3002";
        }

        ObjectNode args = OBJECT_MAPPER.createObjectNode();
        args.put("title", title.trim());
        args.put("content", content.trim());
        args.put("video", videoUrl.trim());
        if (!isBlank(coverUrl)) {
            args.put("cover", coverUrl.trim());
        }
        args.set("tags", toArray(normalizeTags(tags)));

        return publish(properties.getPublishVideoToolName(), args, "\u89c6\u9891", dryRun);
    }

    @Tool(name = "xiaohongshu_get_note_detail",
          description = "Read Xiaohongshu note detail by feed_id and xsec_token.")
    public String getNoteDetail(
            @ToolParam(description = "Xiaohongshu feed_id, note URL, or note id") String noteUrlOrId,
            @ToolParam(description = "Xiaohongshu xsec_token from search/list result") String xsecToken) {
        if (isBlank(noteUrlOrId)) {
            return "\u8bfb\u53d6\u5c0f\u7ea2\u4e66\u7b14\u8bb0\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b feed_id\u3001\u7b14\u8bb0\u94fe\u63a5\u6216\u7b14\u8bb0ID\u3002";
        }

        try {
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("feed_id", extractFeedId(noteUrlOrId.trim()));
            if (!isBlank(xsecToken)) {
                args.put("xsec_token", xsecToken.trim());
            }
            JsonNode result = client.callTool(properties.getDetailToolName(), args);
            return formatDetail(client.getTextContent(result), client.getStructuredContent(result));
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu get note detail failed: {}", noteUrlOrId, e);
            return "\u8bfb\u53d6\u5c0f\u7ea2\u4e66\u7b14\u8bb0\u5931\u8d25\uff1a" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu get note detail unexpected error: {}", noteUrlOrId, e);
            return "\u8bfb\u53d6\u5c0f\u7ea2\u4e66\u7b14\u8bb0\u5f02\u5e38\uff1a" + e.getMessage();
        }
    }

    @Tool(name = "xiaohongshu_search_notes",
          description = "Search Xiaohongshu notes through MCP search_feeds.")
    public String searchNotes(
            @ToolParam(description = "Search keyword") String keyword,
            @ToolParam(description = "Number of results to show locally; not sent to MCP") Integer limit) {
        if (isBlank(keyword)) {
            return "\u641c\u7d22\u5c0f\u7ea2\u4e66\u5931\u8d25\uff1a\u8bf7\u63d0\u4f9b\u641c\u7d22\u5173\u952e\u8bcd\u3002";
        }

        try {
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("keyword", keyword.trim());

            JsonNode result = client.callTool(properties.getSearchToolName(), args);
            return formatSearchResults(keyword.trim(), client.getTextContent(result),
                    client.getStructuredContent(result), limit);
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu search failed: {}", keyword, e);
            return formatMcpFailure("\u641c\u7d22\u5c0f\u7ea2\u4e66", e);
        } catch (Exception e) {
            log.warn("xiaohongshu search unexpected error: {}", keyword, e);
            return formatMcpFailure("\u641c\u7d22\u5c0f\u7ea2\u4e66", e);
        }
    }

    @Tool(name = "xiaohongshu_comment_note",
          description = "Comment on a Xiaohongshu note. Use only when the user explicitly asks to post a comment.")
    public String commentNote(
            @ToolParam(description = "Xiaohongshu feed_id or note URL") String feedIdOrUrl,
            @ToolParam(description = "Xiaohongshu xsec_token") String xsecToken,
            @ToolParam(description = "Comment content to post") String content,
            @ToolParam(description = "Dry run only; true means MCP is not called") Boolean dryRun) {
        if (isBlank(feedIdOrUrl) || isBlank(xsecToken) || isBlank(content)) {
            return "评论小红书笔记失败：feed_id、xsec_token 和评论内容都不能为空。";
        }
        ObjectNode args = feedArgs(feedIdOrUrl, xsecToken);
        args.put("content", content.trim());
        return accountAction(properties.getPostCommentToolName(), args, "评论预览", "评论已发布", dryRun);
    }

    @Tool(name = "xiaohongshu_reply_comment",
          description = "Reply to a specific Xiaohongshu comment. Use only when the user explicitly asks to post a reply.")
    public String replyComment(
            @ToolParam(description = "Xiaohongshu feed_id or note URL") String feedIdOrUrl,
            @ToolParam(description = "Xiaohongshu xsec_token") String xsecToken,
            @ToolParam(description = "Target comment_id") String commentId,
            @ToolParam(description = "Target user_id when comment_id is unavailable") String userId,
            @ToolParam(description = "Reply content to post") String content,
            @ToolParam(description = "Dry run only; true means MCP is not called") Boolean dryRun) {
        if (isBlank(feedIdOrUrl) || isBlank(xsecToken) || isBlank(content)) {
            return "回复小红书评论失败：feed_id、xsec_token 和回复内容都不能为空。";
        }
        if (isBlank(commentId) && isBlank(userId)) {
            return "回复小红书评论失败：comment_id 和 user_id 至少需要提供一个。";
        }
        ObjectNode args = feedArgs(feedIdOrUrl, xsecToken);
        if (!isBlank(commentId)) {
            args.put("comment_id", commentId.trim());
        } else {
            args.put("user_id", userId.trim());
        }
        args.put("content", content.trim());
        return accountAction(properties.getReplyCommentToolName(), args, "回复预览", "回复已发布", dryRun);
    }

    @Tool(name = "xiaohongshu_get_note_detail_with_comments",
          description = "Read Xiaohongshu note details with interaction metrics, comments, and replies.")
    public String getNoteDetailWithComments(
            @ToolParam(description = "Xiaohongshu feed_id or note URL") String feedIdOrUrl,
            @ToolParam(description = "Xiaohongshu xsec_token") String xsecToken,
            @ToolParam(description = "Whether to load all comments") Boolean loadAllComments,
            @ToolParam(description = "Maximum comments to include") Integer limit,
            @ToolParam(description = "Whether to click/load more replies") Boolean clickMoreReplies,
            @ToolParam(description = "Maximum replies per comment") Integer replyLimit,
            @ToolParam(description = "MCP scroll speed, such as slow/normal/fast") String scrollSpeed) {
        if (isBlank(feedIdOrUrl) || isBlank(xsecToken)) {
            return "读取小红书笔记详情失败：feed_id 和 xsec_token 都不能为空。";
        }
        try {
            ObjectNode args = feedArgs(feedIdOrUrl, xsecToken);
            if (loadAllComments != null) {
                args.put("load_all_comments", loadAllComments);
            }
            if (limit != null && limit > 0) {
                args.put("limit", limit);
            }
            if (clickMoreReplies != null) {
                args.put("click_more_replies", clickMoreReplies);
            }
            if (replyLimit != null && replyLimit > 0) {
                args.put("reply_limit", replyLimit);
            }
            if (!isBlank(scrollSpeed)) {
                args.put("scroll_speed", scrollSpeed.trim());
            }
            JsonNode result = client.callTool(properties.getDetailToolName(), args);
            return formatDetailWithComments(client.getTextContent(result), client.getStructuredContent(result));
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu get note detail with comments failed: {}", feedIdOrUrl, e);
            return "读取小红书笔记详情失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu get note detail with comments unexpected error: {}", feedIdOrUrl, e);
            return "读取小红书笔记详情异常：" + e.getMessage();
        }
    }

    @Tool(name = "xiaohongshu_get_user_profile",
          description = "Read a Xiaohongshu user's profile, account stats, and note list.")
    public String getUserProfile(
            @ToolParam(description = "Xiaohongshu user_id or profile URL") String userIdOrProfileUrl,
            @ToolParam(description = "Optional Xiaohongshu xsec_token") String xsecToken,
            @ToolParam(description = "Maximum notes to show locally") Integer limit) {
        if (isBlank(userIdOrProfileUrl)) {
            return "读取小红书用户主页失败：请提供用户ID或主页链接。";
        }
        try {
            ObjectNode args = OBJECT_MAPPER.createObjectNode();
            args.put("user_id", extractIdFromUrl(userIdOrProfileUrl));
            if (!isBlank(xsecToken)) {
                args.put("xsec_token", xsecToken.trim());
            }
            if (limit != null && limit > 0) {
                args.put("limit", limit);
            }
            JsonNode result = client.callTool(properties.getUserProfileToolName(), args);
            return formatUserProfile(client.getTextContent(result), client.getStructuredContent(result), limit);
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu get user profile failed: {}", userIdOrProfileUrl, e);
            return "读取小红书用户主页失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu get user profile unexpected error: {}", userIdOrProfileUrl, e);
            return "读取小红书用户主页异常：" + e.getMessage();
        }
    }

    @Tool(name = "xiaohongshu_like_note",
          description = "Like a Xiaohongshu note. Use only when the user explicitly asks to like it.")
    public String likeNote(String feedIdOrUrl, String xsecToken, Boolean dryRun) {
        return feedToggle(properties.getLikeToolName(), feedIdOrUrl, xsecToken,
                "点赞预览", "点赞完成", null, dryRun);
    }

    @Tool(name = "xiaohongshu_unlike_note",
          description = "Unlike a Xiaohongshu note. Use only when the user explicitly asks to cancel a like.")
    public String unlikeNote(String feedIdOrUrl, String xsecToken, Boolean dryRun) {
        return feedToggle(properties.getLikeToolName(), feedIdOrUrl, xsecToken,
                "取消点赞预览", "取消点赞完成", "unlike", dryRun);
    }

    @Tool(name = "xiaohongshu_favorite_note",
          description = "Favorite a Xiaohongshu note. Use only when the user explicitly asks to favorite it.")
    public String favoriteNote(String feedIdOrUrl, String xsecToken, Boolean dryRun) {
        return feedToggle(properties.getFavoriteToolName(), feedIdOrUrl, xsecToken,
                "收藏预览", "收藏完成", null, dryRun);
    }

    @Tool(name = "xiaohongshu_unfavorite_note",
          description = "Unfavorite a Xiaohongshu note. Use only when the user explicitly asks to cancel a favorite.")
    public String unfavoriteNote(String feedIdOrUrl, String xsecToken, Boolean dryRun) {
        return feedToggle(properties.getFavoriteToolName(), feedIdOrUrl, xsecToken,
                "取消收藏预览", "取消收藏完成", "unfavorite", dryRun);
    }

    private String publish(String toolName, ObjectNode args, String typeLabel, Boolean dryRun) {
        if (properties.isDryRun() || Boolean.TRUE.equals(dryRun)) {
            recordPublishInvocation(toolName, "xiaohongshu publish dry run; MCP was not called", false);
            return "\u3010\u5c0f\u7ea2\u4e66" + typeLabel + "\u53d1\u5e03\u9884\u89c8\u3011\n" + pretty(args);
        }

        try {
            recordPublishInvocation(toolName, "xiaohongshu MCP publish call started", true);
            JsonNode result = client.callTool(toolName, args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);
            StringBuilder sb = new StringBuilder("\u3010\u5c0f\u7ea2\u4e66").append(typeLabel).append("\u53d1\u5e03\u5b8c\u6210\u3011");
            appendField(sb, "\u7b14\u8bb0ID", firstText(structured, "noteId", "note_id", "id"));
            appendField(sb, "\u94fe\u63a5", firstText(structured, "url", "noteUrl", "note_url", "shareUrl", "share_url"));
            if (text != null && !text.isBlank()) {
                sb.append("\n\n").append(text.trim());
            }
            String output = sb.toString().trim();
            recordPublishInvocation(toolName, output, true);
            return output;
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu publish failed: toolName={}, args={}", toolName, args, e);
            recordPublishInvocation(toolName, "xiaohongshu MCP publish failed: " + e.getMessage(), true);
            return "\u53d1\u5e03\u5c0f\u7ea2\u4e66\u5931\u8d25\uff1a" + e.getMessage();
        } catch (Exception e) {
            log.warn("xiaohongshu publish unexpected error: toolName={}, args={}", toolName, args, e);
            recordPublishInvocation(toolName, "xiaohongshu MCP publish failed: " + e.getMessage(), true);
            return "\u53d1\u5e03\u5c0f\u7ea2\u4e66\u5f02\u5e38\uff1a" + e.getMessage();
        }
    }

    private String feedToggle(String toolName, String feedIdOrUrl, String xsecToken,
                              String previewLabel, String successLabel,
                              String undoFlagName, Boolean dryRun) {
        if (isBlank(feedIdOrUrl) || isBlank(xsecToken)) {
            return successLabel + "失败：feed_id 和 xsec_token 都不能为空。";
        }
        ObjectNode args = feedArgs(feedIdOrUrl, xsecToken);
        if (!isBlank(undoFlagName)) {
            args.put(undoFlagName, true);
        }
        return accountAction(toolName, args, previewLabel, successLabel, dryRun);
    }

    private String accountAction(String toolName, ObjectNode args, String previewLabel,
                                 String successLabel, Boolean dryRun) {
        if (properties.isDryRun() || Boolean.TRUE.equals(dryRun)) {
            String output = "【小红书" + previewLabel + "】\n" + pretty(args);
            recordAccountActionInvocation(toolName, output, false);
            return output;
        }
        try {
            recordAccountActionInvocation(toolName, "xiaohongshu MCP account action call started", true);
            JsonNode result = client.callTool(toolName, args);
            String text = client.getTextContent(result);
            JsonNode structured = client.getStructuredContent(result);
            StringBuilder sb = new StringBuilder("【小红书").append(successLabel).append("】");
            appendField(sb, "评论ID", firstText(structured, "commentId", "comment_id", "id"));
            appendField(sb, "笔记ID", firstText(structured, "feedId", "feed_id", "noteId", "note_id"));
            appendField(sb, "链接", firstText(structured, "url", "noteUrl", "note_url", "shareUrl", "share_url"));
            if (text != null && !text.isBlank()) {
                sb.append("\n\n").append(text.trim());
            }
            String output = sb.toString().trim();
            recordAccountActionInvocation(toolName, output, true);
            return output;
        } catch (XiaohongshuMcpClient.XiaohongshuMcpException e) {
            log.warn("xiaohongshu account action failed: toolName={}, args={}", toolName, args, e);
            String output = "执行小红书操作失败：" + e.getMessage();
            recordAccountActionInvocation(toolName, output, true);
            return output;
        } catch (Exception e) {
            log.warn("xiaohongshu account action unexpected error: toolName={}, args={}", toolName, args, e);
            String output = "执行小红书操作异常：" + e.getMessage();
            recordAccountActionInvocation(toolName, output, true);
            return output;
        }
    }

    private static ObjectNode feedArgs(String feedIdOrUrl, String xsecToken) {
        ObjectNode args = OBJECT_MAPPER.createObjectNode();
        args.put("feed_id", extractFeedId(feedIdOrUrl));
        args.put("xsec_token", xsecToken.trim());
        return args;
    }

    private String formatDetail(String text, JsonNode structured) {
        StringBuilder sb = new StringBuilder("\u3010\u5c0f\u7ea2\u4e66\u3011\u7b14\u8bb0\u8be6\u60c5");
        appendField(sb, "\u6807\u9898", firstText(structured, "title", "displayTitle", "display_title"));
        appendField(sb, "\u4f5c\u8005", firstText(structured, "author", "nickname", "userName", "user_name"));
        appendField(sb, "\u6b63\u6587", firstText(structured, "content", "desc", "description", "text"));
        appendField(sb, "\u89c6\u9891", firstText(structured, "videoUrl", "video_url", "video", "videoLink", "video_link"));

        List<String> images = collectArrayTexts(structured, "imageUrls", "image_urls", "images", "imageList", "image_list");
        if (!images.isEmpty()) {
            sb.append("\n\u56fe\u7247\uff1a");
            for (int i = 0; i < images.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(images.get(i));
            }
        }
        if (text != null && !text.isBlank()) {
            sb.append("\n\n\u539f\u59cb\u8fd4\u56de\uff1a\n").append(limitText(text.trim(), 2000));
        }
        return sb.toString().trim();
    }

    private String formatDetailWithComments(String text, JsonNode structured) {
        StringBuilder sb = new StringBuilder(formatDetail("", structured));
        JsonNode interact = firstObject(structured, "interactInfo", "interact_info", "interaction", "stats");
        List<String> metrics = new ArrayList<>();
        addMetric(metrics, "点赞", firstText(interact, "likedCount", "liked_count", "likeCount", "like_count"));
        addMetric(metrics, "收藏", firstText(interact, "collectedCount", "collected_count", "favoriteCount", "favorite_count"));
        addMetric(metrics, "评论", firstText(interact, "commentCount", "comment_count"));
        addMetric(metrics, "分享", firstText(interact, "sharedCount", "shared_count", "shareCount", "share_count"));
        if (!metrics.isEmpty()) {
            sb.append("\n互动：").append(String.join(" | ", metrics));
        }

        JsonNode comments = firstArray(structured, "commentList", "comment_list", "comments", "data.comments");
        if (comments != null && comments.isArray() && !comments.isEmpty()) {
            sb.append("\n评论：");
            for (int i = 0; i < comments.size(); i++) {
                appendComment(sb, comments.get(i), i + 1, false);
                JsonNode replies = firstArray(comments.get(i), "subComments", "sub_comments", "replies", "subCommentList");
                if (replies != null && replies.isArray()) {
                    for (JsonNode reply : replies) {
                        appendComment(sb, reply, 0, true);
                    }
                }
            }
        }
        if (text != null && !text.isBlank()) {
            sb.append("\n\n原始返回：\n").append(limitText(text.trim(), 2000));
        }
        return sb.toString().trim();
    }

    private static void appendComment(StringBuilder sb, JsonNode comment, int index, boolean reply) {
        JsonNode user = firstObject(comment, "userInfo", "user_info", "user", "author");
        String nickname = defaultText(firstText(user, "nickname", "name", "userName", "user_name"),
                firstText(comment, "nickname", "userName", "user_name"));
        String content = firstText(comment, "content", "text", "desc");
        String id = firstText(comment, "id", "commentId", "comment_id");
        String userId = defaultText(firstText(user, "userId", "user_id", "id"),
                firstText(comment, "userId", "user_id"));
        if (reply) {
            sb.append("\n   - ");
        } else {
            sb.append("\n").append(index).append(". ");
        }
        if (!nickname.isBlank()) {
            sb.append(nickname).append("：");
        }
        sb.append(defaultText(content, "（无内容）"));
        if (!id.isBlank() || !userId.isBlank()) {
            sb.append("\n   参数：");
            if (!id.isBlank()) {
                sb.append(" comment_id=").append(id);
            }
            if (!userId.isBlank()) {
                sb.append(" user_id=").append(userId);
            }
        }
    }

    private String formatUserProfile(String text, JsonNode structured, Integer limit) {
        JsonNode basic = firstObject(structured, "userBasicInfo", "user_basic_info", "basicInfo", "user", "profile");
        JsonNode stats = firstObject(structured, "userInteractions", "user_interactions", "interactions", "stats");
        StringBuilder sb = new StringBuilder("【小红书】用户主页");
        appendField(sb, "昵称", firstText(basic, "nickname", "name", "userName", "user_name"));
        appendField(sb, "简介", firstText(basic, "desc", "description", "bio", "signature"));
        appendField(sb, "头像", firstText(basic, "avatar", "image", "imageb", "avatarUrl", "avatar_url"));
        appendField(sb, "关注", firstText(stats, "follows", "following", "followCount", "follow_count"));
        appendField(sb, "粉丝", firstText(stats, "fans", "followers", "fansCount", "fans_count"));
        appendField(sb, "获赞", firstText(stats, "interaction", "liked", "likedCount", "liked_count"));

        JsonNode feeds = firstArray(structured, "feeds", "notes", "items", "data.feeds", "data.notes");
        if (feeds != null && feeds.isArray() && !feeds.isEmpty()) {
            int effectiveLimit = limit != null && limit > 0 ? limit : 5;
            sb.append("\n笔记：");
            int count = Math.min(effectiveLimit, feeds.size());
            for (int i = 0; i < count; i++) {
                JsonNode feed = feeds.get(i);
                JsonNode card = firstObject(feed, "noteCard", "note_card", "card");
                String title = defaultText(firstText(card, "displayTitle", "title"),
                        firstText(feed, "displayTitle", "title"));
                sb.append("\n").append(i + 1).append(". ").append(defaultText(title, "未命名笔记"));
                appendSearchLine(sb, "详情参数",
                        compactDetailParams(firstText(feed, "id", "feed_id", "feedId"),
                                firstText(feed, "xsecToken", "xsec_token")));
            }
        }
        if (text != null && !text.isBlank()) {
            sb.append("\n\n原始返回：\n").append(limitText(text.trim(), 2000));
        }
        return sb.toString().trim();
    }

    private String formatSearchResults(String keyword, String text, JsonNode structured, Integer limit) {
        int effectiveLimit = limit != null && limit > 0 ? limit : 5;
        effectiveLimit = Math.min(effectiveLimit, Math.max(1, properties.getMaxImageCount()));

        JsonNode resultRoot = structured;
        JsonNode feeds = firstArray(resultRoot, "data.feeds", "feeds", "data.list", "list", "items", "results");
        if ((feeds == null || feeds.isEmpty()) && text != null && !text.isBlank()) {
            JsonNode parsedText = parseJsonObject(text);
            if (parsedText != null && !parsedText.isMissingNode()) {
                resultRoot = parsedText;
                feeds = firstArray(resultRoot, "data.feeds", "feeds", "data.list", "list", "items", "results");
            }
        }
        if (feeds != null && feeds.isArray() && !feeds.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int count = Math.min(effectiveLimit, feeds.size());
            sb.append("\u3010\u5c0f\u7ea2\u4e66\u3011\u627e\u5230 ")
                    .append(count)
                    .append(" \u6761\u300c")
                    .append(keyword)
                    .append("\u300d\u76f8\u5173\u7b14\u8bb0");
            for (int i = 0; i < count; i++) {
                JsonNode feed = feeds.get(i);
                JsonNode card = firstObject(feed, "noteCard", "note_card", "card");
                JsonNode user = firstObject(card, "user", "author");
                if (user.isMissingNode()) {
                    user = firstObject(feed, "user", "author");
                }
                JsonNode interact = firstObject(card, "interactInfo", "interact_info");
                if (interact.isMissingNode()) {
                    interact = firstObject(feed, "interactInfo", "interact_info");
                }

                String title = defaultText(firstText(card, "displayTitle", "title"),
                        firstText(feed, "displayTitle", "title"));
                String noteType = firstText(card, "type", "noteType", "note_type");
                sb.append("\n\n").append(i + 1).append(". ").append(defaultText(title, "\u672a\u547d\u540d\u7b14\u8bb0"));
                appendSearchLine(sb, "\u4f5c\u8005", defaultText(
                        firstText(user, "nickname", "name", "userName", "user_name"),
                        firstText(feed, "nickname", "author")));
                appendSearchLine(sb, "\u7c7b\u578b", noteType);
                appendMetricLine(sb, interact);
                appendSearchLine(sb, "\u8be6\u60c5\u53c2\u6570",
                        compactDetailParams(firstText(feed, "id", "feed_id", "feedId"),
                                firstText(feed, "xsecToken", "xsec_token")));
            }
            sb.append("\n\n\u60f3\u770b\u67d0\u6761\u8be6\u60c5\u65f6\uff0c\u628a\u5bf9\u5e94\u7684\u300c\u8be6\u60c5\u53c2\u6570\u300d\u53d1\u7ed9\u6211\u5373\u53ef\u3002");
            return sb.toString();
        }

        String fallback = text != null && !text.isBlank() ? text.trim() : pretty(structured);
        if (fallback == null || fallback.isBlank() || "{}".equals(fallback)) {
            return "\u3010\u5c0f\u7ea2\u4e66\u3011\u6ca1\u6709\u641c\u7d22\u5230\u7ed3\u679c\u3002";
        }
        return "\u3010\u5c0f\u7ea2\u4e66\u3011\u641c\u7d22\u7ed3\u679c\uff1a" + keyword
                + "\n\n" + limitText(fallback, 3000)
                + "\n\n\u63d0\u793a\uff1a\u8bfb\u53d6\u8be6\u60c5\u9700\u8981 feed_id \u548c xsec_token\u3002";
    }

    private static void recordPublishInvocation(String toolName, String result, boolean attemptedMcp) {
        PublishCaptureState state = PUBLISH_CAPTURE.get();
        if (state != null) {
            state.invoked = true;
            state.mcpAttempted = attemptedMcp;
            state.lastToolName = toolName;
            state.lastResult = result;
        }
    }

    private static void recordAccountActionInvocation(String toolName, String result, boolean attemptedMcp) {
        AccountActionCaptureState state = ACCOUNT_ACTION_CAPTURE.get();
        if (state != null) {
            state.invoked = true;
            state.mcpAttempted = attemptedMcp;
            state.lastToolName = toolName;
            state.lastResult = result;
        }
    }

    private static String formatMcpFailure(String actionLabel, Exception e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "";
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("context deadline exceeded")
                || normalized.contains("read timed out")
                || normalized.contains("timed out")
                || normalized.contains("timeout")) {
            return actionLabel + "\u5931\u8d25\uff1a\u5c0f\u7ea2\u4e66 MCP \u5728\u7b49\u5f85\u9875\u9762\u6216\u641c\u7d22\u7ed3\u679c\u65f6\u8d85\u65f6\u4e86\u3002"
                    + "\n\u8fd9\u901a\u5e38\u662f MCP \u5185\u90e8\u6d4f\u89c8\u5668\u64cd\u4f5c\u8d85\u65f6\uff0c\u4e0d\u662f Java \u7f16\u6392\u5c42\u5224\u65ad\u6210\u529f\u3002"
                    + "\n\u5efa\u8bae\uff1a\u91cd\u8bd5\u4e00\u6b21\uff0c\u6216\u628a\u641c\u7d22\u8bcd\u6539\u5f97\u66f4\u5177\u4f53\uff1b\u5982\u679c\u8fde\u7eed\u51fa\u73b0\uff0c\u91cd\u542f\u5c0f\u7ea2\u4e66 MCP/\u6d4f\u89c8\u5668\u540e\u518d\u8bd5\u3002"
                    + rawMcpMessage(message);
        }
        if (normalized.contains("tool handler panicked")
                || message.contains("\u5de5\u5177\u5185\u90e8\u53d1\u751f\u9519\u8bef")) {
            return actionLabel + "\u5931\u8d25\uff1a\u5c0f\u7ea2\u4e66 MCP \u5de5\u5177\u5185\u90e8\u6267\u884c\u5931\u8d25\u3002"
                    + "\n\u5efa\u8bae\uff1a\u91cd\u8bd5\u4e00\u6b21\uff0c\u5fc5\u8981\u65f6\u91cd\u542f MCP \u8fdb\u7a0b\u6216\u91cd\u65b0\u767b\u5f55\u5c0f\u7ea2\u4e66\u3002"
                    + rawMcpMessage(message);
        }
        if (message.isBlank()) {
            return actionLabel + "\u5931\u8d25\uff1a\u5c0f\u7ea2\u4e66 MCP \u6ca1\u6709\u8fd4\u56de\u5177\u4f53\u9519\u8bef\u4fe1\u606f\u3002";
        }
        return actionLabel + "\u5931\u8d25\uff1a" + message;
    }

    private static String rawMcpMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "\n\u539f\u59cb\u9519\u8bef\uff1a" + limitText(message.trim(), 500);
    }

    private static List<String> normalizeTags(String raw) {
        List<String> tags = splitList(raw, Integer.MAX_VALUE);
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            String value = tag.trim();
            while (value.startsWith("#")) {
                value = value.substring(1).trim();
            }
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static List<String> splitList(String raw, int maxItems) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        List<String> values = new ArrayList<>();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(trimmed);
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        addListValue(values, item.asText(""));
                    }
                    return limit(values, maxItems);
                }
            } catch (Exception ignored) {
                // Fall through to separator-based parsing.
            }
        }

        for (String item : trimmed.split("[,，;；\\r\\n]+")) {
            addListValue(values, item);
        }
        return limit(values, maxItems);
    }

    private static void addListValue(List<String> values, String value) {
        if (value != null && !value.trim().isBlank()) {
            values.add(value.trim());
        }
    }

    private static List<String> limit(List<String> values, int maxItems) {
        int safeMax = Math.max(0, maxItems);
        if (values.size() <= safeMax) {
            return values;
        }
        return new ArrayList<>(values.subList(0, safeMax));
    }

    private static ArrayNode toArray(List<String> values) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonNode firstArray(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode value = path(node, path);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode firstObject(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return OBJECT_MAPPER.missingNode();
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return OBJECT_MAPPER.missingNode();
    }

    private static JsonNode path(JsonNode node, String dottedPath) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return OBJECT_MAPPER.missingNode();
        }
        JsonNode current = node;
        for (String part : dottedPath.split("\\.")) {
            current = current.path(part);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private static void appendSearchLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n   ").append(label).append(": ").append(value);
        }
    }

    private static void appendMetricLine(StringBuilder sb, JsonNode interact) {
        List<String> metrics = new ArrayList<>();
        addMetric(metrics, "\u70b9\u8d5e", firstText(interact, "likedCount", "liked_count", "likeCount", "like_count"));
        addMetric(metrics, "\u6536\u85cf", firstText(interact, "collectedCount", "collected_count", "favoriteCount", "favorite_count"));
        addMetric(metrics, "\u8bc4\u8bba", firstText(interact, "commentCount", "comment_count"));
        if (!metrics.isEmpty()) {
            sb.append("\n   \u6570\u636e: ").append(String.join(" | ", metrics));
        }
    }

    private static void addMetric(List<String> metrics, String label, String value) {
        if (value != null && !value.isBlank()) {
            metrics.add(label + " " + value);
        }
    }

    private static String compactDetailParams(String feedId, String xsecToken) {
        if (isBlank(feedId) && isBlank(xsecToken)) {
            return "";
        }
        if (isBlank(xsecToken)) {
            return "feed_id=" + feedId;
        }
        if (isBlank(feedId)) {
            return "xsec_token=" + xsecToken;
        }
        return "feed_id=" + feedId + " ; xsec_token=" + xsecToken;
    }

    private static JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return OBJECT_MAPPER.missingNode();
        }
        String text = raw.trim();
        int start = firstJsonStart(text);
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return OBJECT_MAPPER.readTree(text);
        } catch (Exception e) {
            return OBJECT_MAPPER.missingNode();
        }
    }

    private static int firstJsonStart(String text) {
        int object = text.indexOf('{');
        int array = text.indexOf('[');
        if (object < 0) {
            return array;
        }
        if (array < 0) {
            return object;
        }
        return Math.min(object, array);
    }

    private static String defaultText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : (fallback != null ? fallback : "");
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n").append(label).append("\uff1a").append(value);
        }
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isNumber() || value.isBoolean()) {
                return value.asText();
            }
        }
        return "";
    }

    private static List<String> collectArrayTexts(JsonNode node, String... names) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isArray()) {
                continue;
            }
            for (JsonNode item : value) {
                if (item.isTextual()) {
                    addListValue(values, item.asText());
                } else {
                    addListValue(values, firstText(item, "url", "src", "imageUrl", "image_url"));
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        return values;
    }

    private static String pretty(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...\n\uff08\u7ed3\u679c\u8fc7\u957f\uff0c\u5df2\u622a\u65ad\uff09";
    }

    private static String extractFeedId(String input) {
        return extractIdFromUrl(input);
    }

    private static String extractIdFromUrl(String input) {
        if (input == null) {
            return "";
        }
        String value = input.trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slashIndex = value.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < value.length() - 1) {
            value = value.substring(slashIndex + 1);
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isTruthy(String value) {
        String normalized = value.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "logged_in".equals(normalized)
                || "\u5df2\u767b\u5f55".equals(value.trim());
    }

    private static class PublishCaptureState {
        private boolean invoked;
        private boolean mcpAttempted;
        private String lastToolName;
        private String lastResult;
    }

    private static class AccountActionCaptureState {
        private boolean invoked;
        private boolean mcpAttempted;
        private String lastToolName;
        private String lastResult;
    }

    public static final class PublishInvocationCapture implements AutoCloseable {
        private final PublishCaptureState previous;
        private final PublishCaptureState current;
        private boolean closed;

        private PublishInvocationCapture(PublishCaptureState previous, PublishCaptureState current) {
            this.previous = previous;
            this.current = current;
        }

        public boolean wasInvoked() {
            return current.invoked;
        }

        public boolean wasMcpAttempted() {
            return current.mcpAttempted;
        }

        public String lastToolName() {
            return current.lastToolName;
        }

        public String lastResult() {
            return current.lastResult;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                PUBLISH_CAPTURE.remove();
            } else {
                PUBLISH_CAPTURE.set(previous);
            }
        }
    }

    public static final class AccountActionInvocationCapture implements AutoCloseable {
        private final AccountActionCaptureState previous;
        private final AccountActionCaptureState current;
        private boolean closed;

        private AccountActionInvocationCapture(AccountActionCaptureState previous, AccountActionCaptureState current) {
            this.previous = previous;
            this.current = current;
        }

        public boolean wasInvoked() {
            return current.invoked;
        }

        public boolean wasMcpAttempted() {
            return current.mcpAttempted;
        }

        public String lastToolName() {
            return current.lastToolName;
        }

        public String lastResult() {
            return current.lastResult;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                ACCOUNT_ACTION_CAPTURE.remove();
            } else {
                ACCOUNT_ACTION_CAPTURE.set(previous);
            }
        }
    }
}
