package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class XiaohongshuToolsTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publishImageMethodHasExpectedToolAnnotation() throws NoSuchMethodException {
        Method method = XiaohongshuTools.class.getMethod(
                "publishImageNote", String.class, String.class, String.class, String.class, Boolean.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("xiaohongshu_publish_image_note");
    }

    @Test
    void loginStatusMethodHasExpectedToolAnnotation() throws NoSuchMethodException {
        Method method = XiaohongshuTools.class.getMethod("checkLoginStatus");
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("xiaohongshu_check_login_status");
    }

    @Test
    void implementsProjectToolWithXiaohongshuCategory() {
        XiaohongshuTools tools = new XiaohongshuTools(new FakeClient(), new XiaohongshuProperties());

        assertThat(tools).isInstanceOf(ProjectTool.class);
        assertThat(tools.category()).isEqualTo("xiaohongshu");
    }

    @Test
    void loginQrcodeMethodHasExpectedToolAnnotation() throws NoSuchMethodException {
        Method method = XiaohongshuTools.class.getMethod("requestLoginQrcode");
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("xiaohongshu_request_login_qrcode");
    }

    @Test
    void imagePublishValidationDoesNotCallMcpWhenImagesMissing() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.publishImageNote("标题", "正文", "", "", false);

        assertThat(result).contains("图片");
        assertThat(client.calls()).isZero();
    }

    @Test
    void imagePublishDryRunReturnsPlannedPayloadWithoutCallingMcp() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.publishImageNote("标题", "正文", "https://img.example.com/a.jpg, https://img.example.com/b.jpg",
                "旅行, 杭州", false);

        assertThat(result)
                .contains("发布预览")
                .contains("https://img.example.com/a.jpg")
                .contains("旅行");
        assertThat(client.calls()).isZero();
    }

    @Test
    void imagePublishCallsConfiguredMcpTool() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        properties.setDryRun(false);
        properties.setPublishImageToolName("publish_image_note");
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String result = tools.publishImageNote("标题", "正文", "https://img.example.com/a.jpg", "#旅行", false);

        assertThat(result).contains("发布完成").contains("note123");
        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.lastToolName()).isEqualTo("publish_image_note");
        assertThat(client.lastArguments().path("images").get(0).asText()).isEqualTo("https://img.example.com/a.jpg");
        assertThat(client.lastArguments().has("imageUrls")).isFalse();
        assertThat(client.lastArguments().path("tags").get(0).asText()).isEqualTo("旅行");
    }

    @Test
    void videoPublishValidationRequiresVideoUrl() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.publishVideoNote("标题", "正文", "", "", "", false);

        assertThat(result).contains("视频");
        assertThat(client.calls()).isZero();
    }

    @Test
    void getNoteDetailFormatsImagesAndVideoFields() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.getNoteDetail("note123", "xsec-test-token");

        assertThat(result)
                .contains("标题：西湖日落")
                .contains("作者：小夏")
                .contains("视频：https://cdn.example.com/video.mp4")
                .contains("https://img.example.com/cover.jpg");
        assertThat(client.lastToolName()).isEqualTo("get_feed_detail");
        assertThat(client.lastArguments().path("feed_id").asText()).isEqualTo("note123");
        assertThat(client.lastArguments().path("xsec_token").asText()).isEqualTo("xsec-test-token");
        assertThat(client.lastArguments().has("noteId")).isFalse();
        assertThat(client.lastArguments().has("note_id")).isFalse();
    }

    @Test
    void searchNotesUsesStrictMcpArgumentsOnly() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String result = tools.searchNotes("露营", 99);

        assertThat(result).contains("\u627e\u5230 1 \u6761");
        assertThat(client.lastArguments().path("keyword").asText()).isEqualTo("露营");
        assertThat(client.lastArguments().has("query")).isFalse();
        assertThat(client.lastArguments().has("limit")).isFalse();
        assertThat(result)
                .contains("feed-a")
                .contains("xsec-a")
                .contains("Alice")
                .doesNotContain("raw text should not be returned directly");
    }

    @Test
    void searchNotesFormatsJsonReturnedAsText() {
        FakeClient client = new FakeClient();
        client.searchAsTextJson = true;
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.searchNotes("photo spot", 1);

        assertThat(result)
                .contains("Text Json Title")
                .contains("Bob")
                .contains("feed-text-json")
                .contains("xsec-text-json")
                .doesNotContain("\"feeds\"")
                .doesNotContain("\"noteCard\"");
    }

    @Test
    void searchNotesReportsMcpTimeoutAsActionableFailure() {
        XiaohongshuTools tools = new XiaohongshuTools(new TimeoutClient(), new XiaohongshuProperties());

        String result = tools.searchNotes("杭州咖啡", 5);

        assertThat(result)
                .contains("\u641c\u7d22\u5c0f\u7ea2\u4e66\u5931\u8d25")
                .contains("MCP")
                .contains("\u8d85\u65f6")
                .contains("context deadline exceeded");
    }

    @Test
    void checkLoginStatusCallsConfiguredMcpToolAndFormatsAccount() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.checkLoginStatus();

        assertThat(result)
                .contains("登录状态")
                .contains("已登录")
                .contains("测试账号");
        assertThat(client.lastToolName()).isEqualTo("check_login_status");
    }

    @Test
    void requestLoginQrcodeReturnsImageMarker() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.requestLoginQrcode();

        assertThat(result)
                .contains("XHS_LOGIN_QR")
                .contains("[XHS_LOGIN_QR:image/png;abc123]");
        assertThat(client.lastToolName()).isEqualTo("get_login_qrcode");
    }

    @Test
    void commentNoteMethodHasExpectedToolAnnotation() throws NoSuchMethodException {
        Method method = XiaohongshuTools.class.getMethod(
                "commentNote", String.class, String.class, String.class, Boolean.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("xiaohongshu_comment_note");
    }

    @Test
    void commentNoteDryRunDoesNotCallMcp() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.commentNote("https://www.xiaohongshu.com/explore/feed123?xsec_token=ignored",
                "xsec-test-token", "写得真好！", false);

        assertThat(result)
                .contains("评论预览")
                .contains("feed123")
                .contains("写得真好");
        assertThat(client.calls()).isZero();
    }

    @Test
    void commentNoteCallsMcpWithFeedIdAndContent() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        properties.setDryRun(false);
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String result = tools.commentNote("feed123", "xsec-test-token", "写得真好！", false);

        assertThat(result).contains("评论已发布");
        assertThat(client.lastToolName()).isEqualTo("post_comment_to_feed");
        assertThat(client.lastArguments().path("feed_id").asText()).isEqualTo("feed123");
        assertThat(client.lastArguments().path("xsec_token").asText()).isEqualTo("xsec-test-token");
        assertThat(client.lastArguments().path("content").asText()).isEqualTo("写得真好！");
    }

    @Test
    void replyCommentRequiresCommentIdOrUserId() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        properties.setDryRun(false);
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String result = tools.replyComment("feed123", "xsec-test-token", "", "", "谢谢支持！", false);

        assertThat(result).contains("comment_id").contains("user_id");
        assertThat(client.calls()).isZero();
    }

    @Test
    void replyCommentCallsMcpWithTargetComment() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        properties.setDryRun(false);
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String result = tools.replyComment("feed123", "xsec-test-token", "comment-1", "", "谢谢支持！", false);

        assertThat(result).contains("回复已发布");
        assertThat(client.lastToolName()).isEqualTo("reply_comment_in_feed");
        assertThat(client.lastArguments().path("comment_id").asText()).isEqualTo("comment-1");
        assertThat(client.lastArguments().path("content").asText()).isEqualTo("谢谢支持！");
        assertThat(client.lastArguments().has("user_id")).isFalse();
    }

    @Test
    void likeAndFavoriteActionsUseBooleanFlagsForUndo() {
        FakeClient client = new FakeClient();
        XiaohongshuProperties properties = new XiaohongshuProperties();
        properties.setDryRun(false);
        XiaohongshuTools tools = new XiaohongshuTools(client, properties);

        String unlikeResult = tools.unlikeNote("feed123", "xsec-test-token", false);
        assertThat(unlikeResult).contains("取消点赞完成");
        assertThat(client.lastToolName()).isEqualTo("like_feed");
        assertThat(client.lastArguments().path("unlike").asBoolean()).isTrue();

        String favoriteResult = tools.favoriteNote("feed123", "xsec-test-token", false);
        assertThat(favoriteResult).contains("收藏完成");
        assertThat(client.lastToolName()).isEqualTo("favorite_feed");
        assertThat(client.lastArguments().has("unfavorite")).isFalse();
    }

    @Test
    void getNoteDetailWithCommentsAddsMcpCommentOptionsAndFormatsCommentIds() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.getNoteDetailWithComments("feed123", "xsec-test-token",
                true, 20, true, 5, "fast");

        assertThat(result)
                .contains("互动")
                .contains("评论")
                .contains("comment-1")
                .contains("写得真好");
        assertThat(client.lastToolName()).isEqualTo("get_feed_detail");
        assertThat(client.lastArguments().path("load_all_comments").asBoolean()).isTrue();
        assertThat(client.lastArguments().path("limit").asInt()).isEqualTo(20);
        assertThat(client.lastArguments().path("click_more_replies").asBoolean()).isTrue();
        assertThat(client.lastArguments().path("reply_limit").asInt()).isEqualTo(5);
        assertThat(client.lastArguments().path("scroll_speed").asText()).isEqualTo("fast");
    }

    @Test
    void userProfileCallsMcpAndFormatsStatsAndFeeds() {
        FakeClient client = new FakeClient();
        XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());

        String result = tools.getUserProfile("user123", "xsec-test-token", 3);

        assertThat(result)
                .contains("用户主页")
                .contains("测试博主")
                .contains("粉丝")
                .contains("feed-a");
        assertThat(client.lastToolName()).isEqualTo("user_profile");
        assertThat(client.lastArguments().path("user_id").asText()).isEqualTo("user123");
        assertThat(client.lastArguments().path("xsec_token").asText()).isEqualTo("xsec-test-token");
    }

    private static class FakeClient extends XiaohongshuMcpClient {

        private int calls;
        private String lastToolName;
        private JsonNode lastArguments;
        private boolean searchAsTextJson;

        FakeClient() {
            super(new RestTemplate(), "https://mcp.example.com/xhs");
        }

        int calls() {
            return calls;
        }

        String lastToolName() {
            return lastToolName;
        }

        JsonNode lastArguments() {
            return lastArguments;
        }

        @Override
        public JsonNode callTool(String toolName, JsonNode arguments) {
            calls++;
            lastToolName = toolName;
            lastArguments = arguments;

            ObjectNode structured = MAPPER.createObjectNode();
            if ("get_login_qrcode".equals(toolName)) {
                ObjectNode root = MAPPER.createObjectNode();
                var content = root.putArray("content");
                content.addObject()
                        .put("type", "text")
                        .put("text", "login with qr code");
                content.addObject()
                        .put("type", "image")
                        .put("mimeType", "image/png")
                        .put("data", "abc123");
                root.set("structuredContent", structured);
                return root;
            }
            if ("get_feed_detail".equals(toolName)) {
                structured.put("title", "西湖日落");
                structured.put("author", "小夏");
                structured.put("content", "今天的光很好");
                structured.put("videoUrl", "https://cdn.example.com/video.mp4");
                structured.putArray("imageUrls").add("https://img.example.com/cover.jpg");
                ObjectNode interact = structured.putObject("interactInfo");
                interact.put("likedCount", "88");
                interact.put("collectedCount", "12");
                interact.put("commentCount", "3");
                interact.put("sharedCount", "4");
                ObjectNode comment = MAPPER.createObjectNode();
                comment.put("id", "comment-1");
                comment.put("content", "写得真好");
                comment.putObject("userInfo").put("nickname", "Alice").put("userId", "user-a");
                comment.putArray("subComments")
                        .addObject()
                        .put("id", "reply-1")
                        .put("content", "谢谢")
                        .putObject("userInfo")
                        .put("nickname", "Bob")
                        .put("userId", "user-b");
                structured.putArray("commentList").add(comment);
                return result("详情文本", structured);
            }
            if ("user_profile".equals(toolName)) {
                ObjectNode basic = structured.putObject("userBasicInfo");
                basic.put("nickname", "测试博主");
                basic.put("desc", "分享生活");
                basic.put("avatar", "https://img.example.com/avatar.jpg");
                ObjectNode stats = structured.putObject("userInteractions");
                stats.put("fans", "1000");
                stats.put("follows", "88");
                stats.put("interaction", "9999");
                ObjectNode feed = MAPPER.createObjectNode();
                feed.put("id", "feed-a");
                feed.put("xsecToken", "xsec-a");
                feed.putObject("noteCard").put("displayTitle", "主页笔记");
                structured.putArray("feeds").add(feed);
                return result("profile text", structured);
            }
            if ("check_login_status".equals(toolName)) {
                structured.put("isLoggedIn", true);
                structured.put("nickname", "测试账号");
                structured.put("userId", "user123");
                return result("当前已登录：测试账号", structured);
            }
            if ("search_feeds".equals(toolName)) {
                if (searchAsTextJson) {
                    return result("""
                            {
                              "feeds": [
                                {
                                  "id": "feed-text-json",
                                  "xsecToken": "xsec-text-json",
                                  "noteCard": {
                                    "displayTitle": "Text Json Title",
                                    "type": "normal",
                                    "user": {"nickname": "Bob"},
                                    "interactInfo": {"likedCount": "123", "collectedCount": "45", "commentCount": "6"}
                                  }
                                }
                              ]
                            }
                            """, MAPPER.createObjectNode());
                }
                ObjectNode feed = MAPPER.createObjectNode();
                feed.put("id", "feed-a");
                feed.put("xsecToken", "xsec-a");
                ObjectNode card = feed.putObject("noteCard");
                card.put("displayTitle", "West Lake photo spot");
                ObjectNode user = card.putObject("user");
                user.put("nickname", "Alice");
                ObjectNode interact = card.putObject("interactInfo");
                interact.put("likedCount", "88");
                ObjectNode data = structured.putObject("data");
                data.putArray("feeds").add(feed);
                return result("raw text should not be returned directly", structured);
            }
            structured.put("noteId", "note123");
            structured.put("url", "https://www.xiaohongshu.com/explore/note123");
            return result("ok", structured);
        }

        @Override
        public String getTextContent(JsonNode result) {
            return result.path("content").get(0).path("text").asText();
        }

        @Override
        public JsonNode getStructuredContent(JsonNode result) {
            return result.path("structuredContent");
        }

        private JsonNode result(String text, ObjectNode structured) {
            ObjectNode root = MAPPER.createObjectNode();
            root.putArray("content").addObject().put("type", "text").put("text", text);
            root.set("structuredContent", structured);
            return root;
        }
    }

    private static class TimeoutClient extends FakeClient {

        @Override
        public JsonNode callTool(String toolName, JsonNode arguments) {
            throw new XiaohongshuMcpException("tool_error",
                    "Tool handler panicked: context deadline exceeded");
        }
    }
}
