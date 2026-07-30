# Xiaohongshu MCP Runtime

The Java app can auto-start a bundled Xiaohongshu MCP runtime when:

```properties
agent.tools.xiaohongshu.process.auto-start=true
agent.tools.xiaohongshu.process.command=auto
agent.tools.xiaohongshu.process.working-directory=
```

Put platform builds in these locations:

```text
tools/xiaohongshu-mcp/windows/xiaohongshu-mcp-windows-amd64.exe
tools/xiaohongshu-mcp/macos/xiaohongshu-mcp-darwin-arm64
tools/xiaohongshu-mcp/linux/xiaohongshu-mcp-linux-amd64
```

Do not commit private browser/session files such as `cookies.json`.
