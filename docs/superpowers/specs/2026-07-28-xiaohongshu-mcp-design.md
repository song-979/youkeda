# Xiaohongshu MCP Tool Design

## Context

The project is a Spring Boot WeChat bot that exposes domain capabilities to Spring AI through classes implementing `ToolService.ProjectTool` and methods annotated with `@Tool`. Existing remote MCP integration is represented by the DiDi taxi tool: a low-level JSON-RPC client calls MCP `tools/call`, and a higher-level tool class exposes safe, user-facing operations.

The requested Xiaohongshu feature should follow the same pattern instead of embedding browser automation directly into this Java service. The referenced MCP server provides capabilities around Xiaohongshu login, publishing posts, publishing videos, listing/searching notes, and reading note details. The Java project will wrap those MCP tools and make them callable from normal user messages.

## Selected Approach

Use a Java wrapper around an already-running Xiaohongshu MCP server.

Alternatives considered:

1. Wrap the MCP server from Java. This is recommended because it matches the existing DiDi pattern, keeps browser/session complexity outside the bot, and makes the integration testable through a mocked JSON-RPC boundary.
2. Reimplement Xiaohongshu browser automation in Java/Playwright. This would avoid an external MCP process, but it expands the blast radius and duplicates the reference project.
3. Expose a separate HTTP controller for manual publishing. This is useful later for admin workflows, but it would not satisfy the current goal of letting the user trigger actions through normal chat input.

## Scope

Add a new `xiaohongshutool` package with:

- `XiaohongshuMcpClient`: JSON-RPC 2.0 client for MCP `tools/call`.
- `XiaohongshuTools`: Spring AI tools for publishing and reading Xiaohongshu content.
- `XiaohongshuProperties`: configuration for endpoint, timeouts, default visibility, output limits, and remote MCP tool names.
- Tests for bean wiring, tool annotations, request mapping, safety gates, and response formatting.

Initial user-facing tools:

- `xiaohongshu_publish_image_note`: publish a graphic/text note from title, content, and image URLs or local image paths.
- `xiaohongshu_publish_video_note`: publish a video note from title, content, video URL/path, and optional cover URL/path.
- `xiaohongshu_get_note_detail`: read a Xiaohongshu note by URL or note ID, returning title, author, text, image URLs, and video URL when present.
- `xiaohongshu_search_notes`: optional discovery helper for searching notes before reading details.

## Data Flow

1. User asks in WeChat to publish or read a Xiaohongshu work.
2. The orchestrator routes the request to the CHAT agent.
3. The CHAT agent chooses the matching `@Tool`.
4. `XiaohongshuTools` validates arguments and applies safety rules.
5. `XiaohongshuMcpClient` sends JSON-RPC to the configured MCP endpoint.
6. The tool formats MCP `content[].text` and `structuredContent` into a concise Chinese response.

Publishing tools will pass through only the data needed by the MCP tool. The Java layer will not store Xiaohongshu cookies or account credentials; those remain owned by the MCP server.

## Local MCP Process Management

The Java project can optionally manage the local Xiaohongshu MCP server process so the user only starts the Spring Boot app in normal use.

- `agent.tools.xiaohongshu.process.auto-start=false` by default.
- When `auto-start=true`, the lifecycle manager first probes the configured MCP endpoint.
- If the endpoint is already reachable, the manager reuses it and does not own or stop that external process.
- If the endpoint is unavailable and a command is configured, the manager starts the command with `ProcessBuilder`, waits until the endpoint becomes reachable, and stores the owned process handle.
- On Spring shutdown, the manager stops only the process it started.
- If no command is configured, the manager logs a clear warning. `fail-on-startup-error=true` can be used when startup should fail fast.

This keeps Xiaohongshu login state, cookies, and browser automation inside the MCP project while making local startup smoother from the bot project.

## Safety Rules

Publishing creates public or account-visible content, so the tools must avoid accidental posting.

- If title, body, or media is missing, return a clear error and do not call MCP.
- If the user asks to "send/publish/post" with all required fields in one message, allow the publish call.
- If the request sounds like drafting, previewing, or asking for advice, do not publish; return generated copy or ask for explicit confirmation.
- Tool descriptions must tell the model to call publish tools only after explicit user intent to publish.
- The Java layer will support a `dryRun` argument or property. When enabled, it returns the planned MCP payload without posting.

## Configuration

Add properties under `agent.tools.xiaohongshu`:

- `enabled`: defaults to `true` so the CHAT agent can see the Xiaohongshu tools.
- `endpoint`: MCP streamable HTTP endpoint, default `http://127.0.0.1:18060/mcp`.
- `connect-timeout-ms`: default 5000.
- `read-timeout-ms`: default 120000 because upload/publish can be slow.
- `dry-run`: default `true` to avoid accidental real-world posting before endpoint/session setup.
- `max-image-count`: default 18.
- `publish-image-tool-name`: default `publish_content`.
- `publish-video-tool-name`: default `publish_with_video`, configurable for MCP servers that expose `publish_video`.
- `detail-tool-name`: default `get_note_detail`.
- `search-tool-name`: default `search_notes`.
- `login-status-tool-name`: default `check_login_status`.
- `process.auto-start`: default `true`.
- `process.command`: default `auto`, or a comma-separated command list for a custom local MCP server.
- `process.working-directory`: directory where the MCP command should run.
- `process.environment`: optional environment variables for the MCP process.
- `process.startup-timeout-ms`: default `30000`.
- `process.stop-timeout-ms`: default `5000`.
- `process.probe-timeout-ms`: default `1000`.
- `process.fail-on-startup-error`: default `false`.

`ToolService` will add category label `xiaohongshu` and register the MCP client/tools when enabled.

## Error Handling

The client will parse JSON-RPC errors and return user-readable messages including the MCP error code and message. Empty or malformed MCP responses become controlled failures. Upload-related timeouts will include a hint to verify the MCP server, Xiaohongshu login state, and media path accessibility.

## Testing

Unit tests will cover:

- JSON-RPC request shape for `tools/call`.
- Extraction of text and structured content from MCP results.
- Tool annotation names.
- Disabled-by-default behavior.
- Bean registration when `agent.tools.xiaohongshu.enabled=true`.
- Validation failures that must not call MCP.
- Formatting for image notes and video note details.

Integration tests will not contact Xiaohongshu or the real MCP server. They will use `MockRestServiceServer` or a fake client.

## Non-Goals

- Do not implement Xiaohongshu login automation in Java.
- Do not persist account cookies in this project.
- Do not download or rehost Xiaohongshu media unless a later requirement asks for file delivery.
- Do not add a web admin UI in this iteration.
