# Xiaohongshu Login QR Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users complete Xiaohongshu MCP login from the WeChat bot by asking for a login QR code, without manually running the MCP login executable.

**Architecture:** Keep the existing HTTP MCP wrapper. `XiaohongshuMcpClient` extracts image content returned by MCP; `XiaohongshuTools` exposes a login QR tool and emits a marker containing base64 image data; `OrchestrationService` converts that marker into a `ModelReply.ImagePayload` so `MessageHandler` sends the QR image through WeChat.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI tool annotations, Jackson, RestTemplate, JUnit 5, AssertJ.

## Global Constraints

- Keep Xiaohongshu cookies and browser automation owned by the external MCP server.
- Do not ask users to run `xiaohongshu-login-windows-amd64.exe` for the normal flow.
- Do not publish when the MCP login state is missing.
- Continue supporting `dry-run=true` for safe publishing previews.
- Tests must not contact Xiaohongshu or a real MCP server.

---

### Task 1: Extract MCP Image Content

**Files:**
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClient.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuMcpClientTests.java`

**Interfaces:**
- Produces `List<McpImageContent> getImageContents(JsonNode result)`.
- `McpImageContent` has `mimeType`, `data`, and `toDataUri()`.

- [x] **Step 1:** Add a failing test with a result containing `{"type":"image","mimeType":"image/png","data":"abc"}` and assert `toDataUri()` returns `data:image/png;base64,abc`.
- [x] **Step 2:** Run `mvn -Dtest=XiaohongshuMcpClientTests test` and confirm the new test fails because the method does not exist.
- [x] **Step 3:** Implement `getImageContents` by scanning `result.content[]` for image entries and accepting `mimeType` or `mime_type`.
- [x] **Step 4:** Re-run the client test and confirm it passes.

### Task 2: Add Login QR Tool

**Files:**
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuProperties.java`
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuTools.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/tool/xiaohongshutool/XiaohongshuToolsTests.java`

**Interfaces:**
- Produces `String requestLoginQrcode()`.
- Adds property `loginQrcodeToolName`, default `get_login_qrcode`.
- Emits marker `[XHS_LOGIN_QR:mimeType;base64]` for the final reply builder.

- [x] **Step 1:** Add a failing annotation test for `@Tool(name="xiaohongshu_request_login_qrcode")`.
- [x] **Step 2:** Add a failing fake-client test that returns one image content and assert the tool output contains `[XHS_LOGIN_QR:image/png;`.
- [x] **Step 3:** Run `mvn -Dtest=XiaohongshuToolsTests test` and confirm the new tests fail.
- [x] **Step 4:** Implement the property and tool method.
- [x] **Step 5:** Re-run `XiaohongshuToolsTests`.

### Task 3: Convert QR Marker To WeChat Image

**Files:**
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/service/OrchestrationService.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/service/MessageRouterTests.java`

**Interfaces:**
- Consumes markers in final chat text: `[XHS_LOGIN_QR:image/png;base64]`.
- Produces a mixed reply with QR text and one image payload named `xiaohongshu-login-qrcode.png`.

- [x] **Step 1:** Add a failing unit test for marker extraction if an accessible test seam already exists; otherwise cover through existing router final-reply behavior.
- [x] **Step 2:** Implement marker parsing in `buildFinalReply`, decode base64, remove the marker from visible text, and append an image payload.
- [x] **Step 3:** Run affected service tests.

### Task 4: Final Verification

**Files:**
- All touched files.

- [x] **Step 1:** Run focused tests: `mvn -Dtest=XiaohongshuMcpClientTests,XiaohongshuToolsTests,MessageRouterTests test`.
- [x] **Step 2:** Run `mvn test`.
- [x] **Step 3:** Report the remaining Windows Defender caveat: this removes manual login command usage, but the third-party MCP browser automation may still require Defender trust/exclusion on Windows.

### Task 5: Direct Login Commands

**Files:**
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/BotAutoConfiguration.java`
- Modify: `src/main/java/com/youkeda/project/wechatproject/bot/service/OrchestrationService.java`
- Test: `src/test/java/com/youkeda/project/wechatproject/bot/service/MessageRouterTests.java`

- [x] **Step 1:** Inject `XiaohongshuTools` into `MessageRouter` as an optional dependency.
- [x] **Step 2:** Route exact login/account commands such as `获取小红书登录二维码` and `发到哪个小红书账号？` directly, without model tool selection.
- [x] **Step 3:** Add router tests proving the direct commands bypass the orchestrator.
