# 架构方案：工具层配置隔离 + 网页通道

## 核心原则

**只在工具层做隔离，其他全不动。** 没有 ChatService，没有中间层。同一个 MessageRouter，同一个 MessageHandler。

---

## 架构图

```
                    微信(iLink)                  网页(HTTP)
                         │                          │
                   MessageHandler              ChatController
                   (不动，照旧)                  (新增，直接调)
                         │                          │
                         └──────────┬───────────────┘
                                    │
                         ┌──────────▼──────────┐
                         │   MessageRouter      │  ← 不动
                         │   OrchestratorAgent  │
                         │   ChatAgent          │
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────┐
                         │     工具层            │  ← 唯一改动点
                         │                      │
                         │  每个工具读自己的配置    │
                         │                      │
                         │  local profile:      │
                         │    个人API Key        │
                         │    LocalFileTools ✅  │
                         │                      │
                         │  server profile:     │
                         │    服务器API Key       │
                         │    LocalFileTools ❌  │
                         └──────────────────────┘
```

**隔离只在配置层**：每个工具通过 `@Value` 读 API key，Spring profile 决定读哪个文件。

---

## 工具 API Key 隔离方式

工具本身不需要改代码，Spring profile 已经天然支持：

```java
// WeatherTools.java - 不需要改
public WeatherTools(WeatherProperties props) {
    this.amapKey = props.getAmapPrivateKey();  // 从配置读
}
```

```yaml
# application-local.yml (个人电脑)
agent.tools.weather.amap-private-key: "用户自己的高德key"

# application-server.yml  (服务器)
agent.tools.weather.amap-private-key: "服务器的高德key"
```

同一个 `WeatherTools` 类，启动时读不同的配置文件，自动拿到不同的 key。

---

## 工具开关隔离

某些工具只在本地有意义，直接关掉即可：

```yaml
# application-server.yml
agent.tools.files.enabled: false       # 线上不开放本地文件
agent.tools.automation.enabled: false  # 线上暂不开放提醒
```

`LocalFileTools` 本身有 `@ConditionalOnProperty(prefix = "agent.tools.files", name = "enabled", havingValue = "true")`，server 模式下直接不创建这个 Bean。

---

## 改动清单

### 一、新增 `ChatController.java`

**文件**: `src/main/java/com/youkeda/project/wechatproject/controller/ChatController.java`

直接注入 `MessageRouter`，不经过中间层：

```java
@RestController
@ConditionalOnProperty(prefix = "web.chat", name = "enabled", havingValue = "true")
public class ChatController {
    private final MessageRouter router;

    public ChatController(MessageRouter router) {
        this.router = router;
    }

    @PostMapping("/api/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ModelReply reply = router.route(request.userId(), request.message(), List.of());
        return ResponseEntity.ok(ChatResponse.from(reply));
    }

    @GetMapping("/chat")
    public String chatPage() {
        // 返回 chat.html
    }
}
```

### 二、新增聊天网页 `chat.html`

**文件**: `src/main/resources/static/chat.html`

单页对话界面，调用 `POST /api/chat`。

### 三、新增配置 `application-server.yml`

**文件**: `src/main/resources/application-server.yml`

```yaml
deploy.mode: server

ilink.enabled: false              # 线上不用微信
web.chat.enabled: true            # 开启网页聊天

agent:
  tools:
    files.enabled: false           # 关本地文件
    automation.enabled: false      # 关提醒

  ai:
    api-key: "服务器OpenAI key"
    # ... 其他服务器配置

  tools:
    weather:
      amap-private-key: "服务器高德key"
```

### 四、ChatController 的 Bean 条件化

**文件**: `src/main/java/com/youkeda/project/wechatproject/bot/BotAutoConfiguration.java`

在配置类上加 `@ComponentScan` 或让 Spring 自动扫描 `ChatController`（它已经在 `controller` 包下，会被自动扫描）。`ChatController` 上的 `@ConditionalOnProperty` 保证只在 server 模式创建。

不需要改 BotAutoConfiguration。

---

## 不改动的文件

| 文件 | 说明 |
|------|------|
| `MessageHandler.java` | 不动，继续直接调 MessageRouter |
| `MessageRouter` | 不动 |
| `OrchestratorAgent/Impl` | 不动 |
| `ChatAgent` | 不动 |
| `ToolService.java` | 不动 |
| `所有工具类` | 不动，只通过配置切换 key |

---

## 文件变更汇总

| 操作 | 文件 | 说明 |
|------|------|------|
| **新增** | `controller/ChatController.java` | 网页聊天 API |
| **新增** | `resources/static/chat.html` | 聊天页面 |
| **新增** | `resources/application-server.yml` | 服务器配置 |
| **不动** | 其余所有代码 | — |

总共新增 3 个文件，**零行现有代码修改**。
