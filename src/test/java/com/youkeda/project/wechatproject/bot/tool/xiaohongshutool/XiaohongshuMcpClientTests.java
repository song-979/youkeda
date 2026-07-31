package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XiaohongshuMcpClientTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void callToolSendsJsonRpcToolsCall() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/xhs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.method").value("tools/call"))
                .andExpect(jsonPath("$.params.name").value("get_note_detail"))
                .andExpect(jsonPath("$.params.arguments.url")
                        .value("https://www.xiaohongshu.com/explore/abc"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}],"structuredContent":{"title":"测试"}}}
                        """, MediaType.APPLICATION_JSON));

        ObjectNode args = MAPPER.createObjectNode();
        args.put("url", "https://www.xiaohongshu.com/explore/abc");

        JsonNode result = new XiaohongshuMcpClient(restTemplate, "https://mcp.example.com/xhs")
                .callTool("get_note_detail", args);

        assertThat(result.path("structuredContent").path("title").asText()).isEqualTo("测试");
        server.verify();
    }

    @Test
    void extractsTextAndStructuredContent() throws Exception {
        JsonNode result = MAPPER.readTree("""
                {"content":[{"type":"text","text":"详情文本"}],"structuredContent":{"videoUrl":"https://cdn.example.com/a.mp4"}}
                """);
        XiaohongshuMcpClient client = new XiaohongshuMcpClient(new RestTemplate(), "https://mcp.example.com/xhs");

        assertThat(client.getTextContent(result)).isEqualTo("详情文本");
        assertThat(client.getStructuredContent(result).path("videoUrl").asText()).endsWith(".mp4");
    }

    @Test
    void extractsImageContentAsDataUri() throws Exception {
        JsonNode result = MAPPER.readTree("""
                {"content":[{"type":"image","mimeType":"image/png","data":"abc123"}]}
                """);
        XiaohongshuMcpClient client = new XiaohongshuMcpClient(new RestTemplate(), "https://mcp.example.com/xhs");

        assertThat(client.getImageContents(result))
                .singleElement()
                .satisfies(image -> {
                    assertThat(image.mimeType()).isEqualTo("image/png");
                    assertThat(image.data()).isEqualTo("abc123");
                    assertThat(image.toDataUri()).isEqualTo("data:image/png;base64,abc123");
                });
    }

    @Test
    void throwsTypedExceptionForJsonRpcError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/xhs"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"not logged in"}}
                        """, MediaType.APPLICATION_JSON));

        XiaohongshuMcpClient client = new XiaohongshuMcpClient(restTemplate, "https://mcp.example.com/xhs");

        assertThatThrownBy(() -> client.callTool("get_note_detail", MAPPER.createObjectNode()))
                .isInstanceOf(XiaohongshuMcpClient.XiaohongshuMcpException.class)
                .hasMessageContaining("not logged in");
        server.verify();
    }

    @Test
    void throwsTypedExceptionForMcpResultErrorContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/xhs"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"\u5de5\u5177\u5185\u90e8\u53d1\u751f\u9519\u8bef\u4e86: context deadline exceeded"}]}}
                        """, MediaType.APPLICATION_JSON));

        XiaohongshuMcpClient client = new XiaohongshuMcpClient(restTemplate, "https://mcp.example.com/xhs");

        assertThatThrownBy(() -> client.callTool("search_feeds", MAPPER.createObjectNode()))
                .isInstanceOf(XiaohongshuMcpClient.XiaohongshuMcpException.class)
                .hasMessageContaining("context deadline exceeded");
        server.verify();
    }
}
