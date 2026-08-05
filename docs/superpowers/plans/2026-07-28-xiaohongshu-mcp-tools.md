# Xiaohongshu MCP Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring AI tools that let the WeChat bot publish Xiaohongshu image/video notes and read Xiaohongshu image/video note details through a configured MCP server.

**Architecture:** Follow the existing DiDi MCP wrapper pattern. A focused `XiaohongshuMcpClient` handles JSON-RPC `tools/call`; `XiaohongshuTools` handles argument validation, safety gates, MCP payload mapping, and Chinese output formatting; `ToolService` wires the feature behind `agent.tools.xiaohongshu.enabled`.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Jackson, RestTemplate, JUnit 5, AssertJ, Spring `MockRestServiceServer`.

## Global Constraints

- Do not implement Xiaohongshu login automation in Java.
- Do not persist Xiaohongshu cookies or account credentials in this project.
- Keep browser/session complexity owned by the external Xiaohongshu MCP server.
- `agent.tools.xiaohongshu.enabled` defaults to `false`.
- `agent.tools.xiaohongshu.read-timeout-ms` defaults to `120000`.
- Publishing tools must return validation errors without calling MCP when title, body, or media is missing.
- Publishing tools must support dry-run mode.
- Tests must not contact Xiaohongshu or a real MCP server.

---

## File Structure

- Create `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuProperties.java`: typed configuration for endpoint, timeouts, dry-run, limits, and MCP tool names.
- Create `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClient.java`: low-level JSON-RPC client plus helpers for `content[].text` and `structuredContent`.
- Create `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuTools.java`: Spring AI `@Tool` methods for publishing image notes, publishing video notes, reading note details, and searching notes.
- Modify `src/main/java/com/youkeda/project/wechatproject/bot/tool/ToolService.java`: add config properties, category label, client bean, and tools bean.
- Modify `src/main/resources/application.properties`: add disabled-by-default Xiaohongshu settings.
- Create `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClientTests.java`: JSON-RPC and response parsing tests.
- Create `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuToolsTests.java`: validation, dry-run, annotation, and formatting tests.
- Modify `src/test/java/com/youkeda/project/wechatproject/bot/tool/ToolServiceTests.java`: assert disabled-by-default and enabled registration behavior.

---

### Task 1: MCP Client

**Files:**
- Create: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClient.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClientTests.java`

**Interfaces:**
- Consumes: `String endpoint`, timeout values in milliseconds, and a Jackson `JsonNode` arguments object.
- Produces:
  - `JsonNode callTool(String toolName, JsonNode arguments)`
  - `String getTextContent(JsonNode result)`
  - `JsonNode getStructuredContent(JsonNode result)`
  - `XiaohongshuMcpException extends RuntimeException`

- [ ] **Step 1: Write failing request-shape test**

```java
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
            .andExpect(jsonPath("$.params.arguments.url").value("https://www.xiaohongshu.com/explore/abc"))
            .andRespond(withSuccess("""
                    {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}],"structuredContent":{"title":"测试"}}}
                    """, MediaType.APPLICATION_JSON));

    ObjectNode args = new ObjectMapper().createObjectNode();
    args.put("url", "https://www.xiaohongshu.com/explore/abc");

    JsonNode result = new XiaohongshuMcpClient(restTemplate, "https://mcp.example.com/xhs")
            .callTool("get_note_detail", args);

    assertThat(result.path("structuredContent").path("title").asText()).isEqualTo("测试");
    server.verify();
}
```

- [ ] **Step 2: Run client tests to verify they fail**

Run: `mvn -Dtest=XiaohongshuMcpClientTests test`

Expected: FAIL because the test class or implementation class does not exist.

- [ ] **Step 3: Implement minimal client**

```java
public JsonNode callTool(String toolName, JsonNode arguments) {
    ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
    requestBody.put("jsonrpc", "2.0");
    requestBody.put("method", "tools/call");
    requestBody.put("id", 1);
    ObjectNode params = OBJECT_MAPPER.createObjectNode();
    params.put("name", toolName);
    params.set("arguments", arguments != null ? arguments : OBJECT_MAPPER.createObjectNode());
    requestBody.set("params", params);
    ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST,
            new HttpEntity<>(requestBody.toString(), jsonHeaders()), String.class);
    JsonNode root = OBJECT_MAPPER.readTree(response.getBody());
    if (root.has("error")) {
        JsonNode error = root.get("error");
        throw new XiaohongshuMcpException(error.path("code").asText("-1"), error.path("message").asText(""));
    }
    return root.path("result");
}
```

- [ ] **Step 4: Add response helper/error tests**

```java
@Test
void extractsTextAndStructuredContent() throws Exception {
    JsonNode result = new ObjectMapper().readTree("""
            {"content":[{"type":"text","text":"详情文本"}],"structuredContent":{"videoUrl":"https://cdn.example.com/a.mp4"}}
            """);
    XiaohongshuMcpClient client = new XiaohongshuMcpClient(new RestTemplate(), "https://mcp.example.com/xhs");
    assertThat(client.getTextContent(result)).isEqualTo("详情文本");
    assertThat(client.getStructuredContent(result).path("videoUrl").asText()).endsWith(".mp4");
}
```

- [ ] **Step 5: Run client tests to verify they pass**

Run: `mvn -Dtest=XiaohongshuMcpClientTests test`

Expected: PASS.

---

### Task 2: Xiaohongshu Tool Methods

**Files:**
- Create: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuProperties.java`
- Create: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuTools.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuToolsTests.java`

**Interfaces:**
- Consumes:
  - `XiaohongshuMcpClient`
  - `XiaohongshuProperties`
- Produces:
  - `String publishImageNote(String title, String content, String imageUrls, String tags, Boolean dryRun)`
  - `String publishVideoNote(String title, String content, String videoUrl, String coverUrl, String tags, Boolean dryRun)`
  - `String getNoteDetail(String noteUrlOrId)`
  - `String searchNotes(String keyword, Integer limit)`

- [ ] **Step 1: Write failing annotation and validation tests**

```java
@Test
void publishImageMethodHasExpectedToolAnnotation() throws NoSuchMethodException {
    Method method = XiaohongshuTools.class.getMethod(
            "publishImageNote", String.class, String.class, String.class, String.class, Boolean.class);
    Tool annotation = method.getAnnotation(Tool.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.name()).isEqualTo("xiaohongshu_publish_image_note");
}

@Test
void imagePublishValidationDoesNotCallMcpWhenImagesMissing() {
    FakeClient client = new FakeClient();
    XiaohongshuTools tools = new XiaohongshuTools(client, new XiaohongshuProperties());
    String result = tools.publishImageNote("标题", "正文", "", "", false);
    assertThat(result).contains("图片");
    assertThat(client.calls()).isZero();
}
```

- [ ] **Step 2: Run tool tests to verify they fail**

Run: `mvn -Dtest=XiaohongshuToolsTests test`

Expected: FAIL because `XiaohongshuTools` and `XiaohongshuProperties` do not exist.

- [ ] **Step 3: Implement properties and validation**

```java
public class XiaohongshuProperties {
    private boolean enabled = false;
    private String endpoint = "http://127.0.0.1:3000/mcp";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private boolean dryRun = false;
    private int maxImageCount = 18;
    private String publishImageToolName = "publish_content";
    private String publishVideoToolName = "publish_with_video";
    private String detailToolName = "get_note_detail";
    private String searchToolName = "search_notes";
}
```

- [ ] **Step 4: Implement publish image/video methods**

```java
@Tool(name = "xiaohongshu_publish_image_note", description = "发布小红书图文作品。仅当用户明确要求发布/发送/上传时调用。")
public String publishImageNote(String title, String content, String imageUrls, String tags, Boolean dryRun) {
    List<String> images = splitList(imageUrls);
    if (isBlank(title) || isBlank(content) || images.isEmpty()) {
        return "发布小红书图文失败：标题、正文和至少一张图片都不能为空。";
    }
    ObjectNode args = OBJECT_MAPPER.createObjectNode();
    args.put("title", title.trim());
    args.put("content", content.trim());
    args.set("images", toArray(images));
    args.set("tags", toArray(splitList(tags)));
    return callPublish(properties.getPublishImageToolName(), args, dryRun);
}
```

- [ ] **Step 5: Implement detail/search methods and formatting**

```java
@Tool(name = "xiaohongshu_get_note_detail", description = "读取小红书图文或视频笔记详情。")
public String getNoteDetail(String noteUrlOrId) {
    if (isBlank(noteUrlOrId)) {
        return "读取小红书笔记失败：请提供笔记链接或笔记ID。";
    }
    ObjectNode args = OBJECT_MAPPER.createObjectNode();
    args.put("url", noteUrlOrId.trim());
    JsonNode result = client.callTool(properties.getDetailToolName(), args);
    return formatDetail(client.getTextContent(result), client.getStructuredContent(result));
}
```

- [ ] **Step 6: Run tool tests to verify they pass**

Run: `mvn -Dtest=XiaohongshuToolsTests test`

Expected: PASS.

---

### Task 3: Spring Wiring and Configuration

**Files:**
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/tool/ToolService.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/youkeda/project/wechatproject/bot/tool/ToolServiceTests.java`

**Interfaces:**
- Consumes:
  - `XiaohongshuProperties`
  - `XiaohongshuMcpClient`
  - `XiaohongshuTools`
- Produces:
  - Spring bean `xiaohongshuMcpClient`
  - Spring bean `xiaohongshuTools`
  - category label `xiaohongshu`

- [ ] **Step 1: Write failing Spring wiring tests**

```java
@Test
void xiaohongshuToolsAreDisabledByDefault() {
    assertThat(context.getBeansOfType(XiaohongshuTools.class)).isEmpty();
}
```

Create a nested `@SpringBootTest` or separate test class with:

```java
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false",
        "agent.tools.xiaohongshu.enabled=true",
        "agent.tools.xiaohongshu.endpoint=https://mcp.example.com/xhs"
})
class XiaohongshuToolWiringEnabledTests {
    @Autowired ApplicationContext context;
    @Autowired ToolRuntime toolRuntime;

    @Test
    void xiaohongshuToolsRegisterWhenEnabled() {
        assertThat(context.getBean(XiaohongshuTools.class)).isNotNull();
        assertThat(toolRuntime.tools()).anyMatch(tool -> tool instanceof XiaohongshuTools);
    }
}
```

- [ ] **Step 2: Run wiring tests to verify they fail**

Run: `mvn -Dtest=ToolServiceTests,XiaohongshuToolWiringEnabledTests test`

Expected: FAIL because the beans and category are not wired.

- [ ] **Step 3: Wire properties and beans**

```java
@EnableConfigurationProperties({
        ToolService.ToolProperties.class,
        AutomationProperties.class,
        MemoryProperties.class,
        WeatherTools.WeatherProperties.class,
        WorldTimeTools.WorldTimeProperties.class,
        XiaohongshuProperties.class
})
```

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnProperty(prefix = "agent.tools.xiaohongshu", name = "enabled", havingValue = "true")
public XiaohongshuMcpClient xiaohongshuMcpClient(XiaohongshuProperties properties) {
    return new XiaohongshuMcpClient(properties);
}

@Bean
@ConditionalOnMissingBean
@ConditionalOnProperty(prefix = "agent.tools.xiaohongshu", name = "enabled", havingValue = "true")
public XiaohongshuTools xiaohongshuTools(XiaohongshuMcpClient client, XiaohongshuProperties properties) {
    return new XiaohongshuTools(client, properties);
}
```

- [ ] **Step 4: Add application properties**

```properties
# ---- 小红书 MCP ----
agent.tools.xiaohongshu.enabled=false
agent.tools.xiaohongshu.endpoint=http://127.0.0.1:3000/mcp
agent.tools.xiaohongshu.dry-run=false
agent.tools.xiaohongshu.max-image-count=18
```

- [ ] **Step 5: Run wiring tests to verify they pass**

Run: `mvn -Dtest=ToolServiceTests,XiaohongshuToolWiringEnabledTests test`

Expected: PASS.

---

### Task 4: Final Verification

**Files:**
- No new files. Validate all files created or modified by Tasks 1-3.

**Interfaces:**
- Consumes all previous tasks.
- Produces a verified implementation ready for user testing with a configured Xiaohongshu MCP endpoint.

- [ ] **Step 1: Run focused Xiaohongshu tests**

Run: `mvn -Dtest=XiaohongshuMcpClientTests,XiaohongshuToolsTests,XiaohongshuToolWiringEnabledTests test`

Expected: PASS.

- [ ] **Step 2: Run affected tool-service tests**

Run: `mvn -Dtest=ToolServiceTests test`

Expected: PASS.

- [ ] **Step 3: Run package-level test selection**

Run: `mvn -Dtest=*Tool*Tests,*ToolTest test`

Expected: PASS or only failures unrelated to Xiaohongshu that already existed before this change.

- [ ] **Step 4: Review git diff**

Run: `git diff -- src/main/java/com/youkeda/project/wechatproject/bot/tool src/main/resources/application.properties src/test/java/com/youkeda/project/wechatproject/bot/tool docs/superpowers/plans/2026-07-28-xiaohongshu-mcp-tools.md`

Expected: Diff only contains Xiaohongshu tool implementation, config, tests, and this plan.

- [ ] **Step 5: Commit implementation**

```bash
git add docs/superpowers/plans/2026-07-28-xiaohongshu-mcp-tools.md \
        src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool \
        src/main/java/com/youkeda/project/wechatproject/bot/tool/ToolService.java \
        src/main/resources/application.properties \
        src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool \
        src/test/java/com/youkeda/project/wechatproject/bot/tool/ToolServiceTests.java
git commit -m "feat: add xiaohongshu mcp tools"
```

Expected: Commit succeeds and excludes unrelated working-tree changes.
