# 基于现有工具的多 Agent 渐进式拆分方案

## 零、原则

1. **只拆现有工具**，不引入新工具，每个 Agent 的工具 100% 来自已有代码
2. **复用现有循环逻辑**，Orchestrator plan/reflect 和 Spring AI tool-calling 内层循环不动
3. **复用现有文件处理**，DocumentService 和附件解析不碰
4. **每个 Phase 可独立上线**，新旧 Agent 通过开关并存
5. **零删除**，所有旧代码保持可用

---

## 一、现状：一张图看清当前结构

```
OrchestrationService.java (1950行，一个文件包揽一切)
│
├─ 数据类 (可独立，但不是重点)
│   ModelReply, AgentResult, AgentTask, TaskScratchpad, UserRequest, OrchestrationResult
│
├─ 接口 (提取优先级最高)
│   AgentUnit, OrchestratorAgent, ConversationMemory
│
├─ Agent 实现 (都已是独立内部类，提取即可)
│   ├─ ChatAgent         ← 持有所有 tool，内层 Spring AI 循环
│   ├─ ImageGenAgent     ← 36行，纯文生图
│   └─ SpeechAgent       ← 68行，纯 TTS
│
├─ OrchestratorAgentImpl ← 426行，plan/reflect 核心
│
└─ MessageRouter         ← 668行，入口路由+执行循环+回复拼装

ToolService.java
│
├─ ToolRuntime           ← 管理所有 ProjectTool Bean
├─ ToolChatClientFactory ← 创建带 tool 的 Spring AI ChatClient
│
└─ 已注册的工具类别:
    ├─ information       → SystemTools, WeatherTools, WorldTimeTools
    ├─ map_navigation    → Amap(PlaceId/AroundSearch/Direction/StaticMap)Tools
    ├─ didi_taxi         → DiDiTaxiTools
    ├─ browser           → BrowserTools (Chrome MCP, 已接好, 15个工具)
    ├─ automation        → AutomationTools
    ├─ local_files       → LocalFileTools
    ├─ media_generation  → MotouTool
    ├─ web_content       → (声明了但空着)
    └─ skill             → SkillTools
```

### 关键发现

- **ImageGenAgent 和 SpeechAgent 已经是独立的 AgentUnit**，Orchestrator 通过 `AgentRegistry` 路由到它们，只是物理上还在同一个文件里
- **ChatAgent 的工具调用完全不分类**，所有 9 个类别的 tool 一股脑注册给 Spring AI，由模型自己选调哪个
- **BrowserAgent 天然该独立**：长超时(30s)、有状态(Chrome 进程)、视觉模型需求，和所有其他 tool 的执行模式完全不同

---

## 二、拆分目标

从现有 3 个 Agent 变为 5 个：

| Agent | 来源 | 工具 | 独立理由 |
|-------|------|------|---------|
| **ChatAgent** | 瘦身现有 | 纯对话+多模态，不做工具调用 | 回归对话本质 |
| **ImageGenAgent** | 直接提取 | 文生图 | 已有独立 AgentUnit |
| **SpeechAgent** | 直接提取 | TTS 语音合成 | 已有独立 AgentUnit |
| **TravelAgent** | 新拆分 | Amap(5个)+天气+滴滴 | 工具集够深，自然独立领域 |
| **BrowserAgent** | 新拆分 | Chrome MCP(15个) | 执行模式完全不同 |

保留在 ChatAgent 内的工具（暂时不动）：
- information: WorldTimeTools（太轻，不值得拆）
- automation: AutomationTools（定时提醒，和对话紧密耦合）
- local_files: LocalFileTools（太轻）
- media_generation: MotouTool（太轻）
- skill: SkillTools（跨领域的元工具，放 ChatAgent 合理）
- web_content: SearchTools, WebParseTools（太轻）

---

## 三、Phase 1：拆文件（零行为变更）

**目标**：把 `OrchestrationService.java` 里的内部类提取为独立文件，**不改任何逻辑**。

### 文件变更

```
新增:
  src/main/java/.../bot/agent/
    ├─ AgentUnit.java              ← 接口，从 OrchestrationService 移出
    ├─ ChatAgent.java              ← 从 OrchestrationService 移出
    ├─ ImageGenAgent.java          ← 从 OrchestrationService 移出
    ├─ SpeechAgent.java            ← 从 OrchestrationService 移出
    └─ AgentRegistry.java          ← 从 OrchestrationService 移出

  src/main/java/.../bot/orchestrator/
    ├─ OrchestratorAgent.java      ← 接口
    ├─ OrchestratorAgentImpl.java  ← 实现
    ├─ OrchestratorProperties.java ← 配置类
    └─ OrchestrationResult.java    ← DTO

  src/main/java/.../bot/router/
    ├─ MessageRouter.java          ← 668行核心逻辑
    └─ IntentRouter.java           ← 新增，轻量分类器（Phase 3 才用）

  src/main/java/.../bot/model/
    ├─ AgentResult.java
    ├─ AgentTask.java
    ├─ TaskScratchpad.java
    ├─ UserRequest.java
    └─ ModelReply.java

  src/main/java/.../bot/memory/
    ├─ ConversationMemory.java     ← 接口
    └─ InMemoryConversationMemory.java

修改:
  OrchestrationService.java        ← 删除已移出的内部类，保留 import
  BotAutoConfiguration.java        ← 更新 import 路径
```

**不碰**：ToolService.java、所有工具类、DocumentService、MessageHandler

### 验收标准
- 编译通过
- 所有 import 正确
- 功能行为完全不变

---

## 四、Phase 2：拆出 TravelAgent

**目标**：把 Amap + 天气 + 滴滴工具从 ChatAgent 的 tool loop 里移出，组合为一个独立 Agent。

### 当前状态

这些工具现在是 Spring AI `@Tool` Bean，由 `ToolChatClientFactory` 统一注册给 ChatAgent。TravelAgent 不再走 Spring AI tool-calling，而是**自己直接调 API**——它本身不需要 LLM 来"选工具"，因为任务已经足够明确。

### TravelAgent 设计

```java
@Component
public class TravelAgent implements AgentUnit {

    @Override public String getName() { return "TRAVEL"; }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability("TRAVEL", "出行助手",
            "处理天气查询、地点搜索、路线规划、导航、打车等出行相关需求");
    }

    @Override
    public AgentResult execute(AgentTask task) {
        // 和 ImageGenAgent/SpeechAgent 一样——不需要 LLM
        // 解析 task.instruction() 中的结构化参数
        // 直接调 AmapApi / DiDiApi / WeatherApi
    }
}
```

### 关键决策：TravelAgent 是否需要自己的 LLM？

**不需要。** 调用链变为：

```
旧: Orchestrator → ChatAgent(LLM tool-calling: 天气/地图/滴滴) → 回复
新: Orchestrator → TravelAgent(直接调 API) → 回复
```

TravelAgent 的工作是"给定明确的参数，调 API 返回结果"，和 ImageGenAgent 生成图片一样，不需要 LLM 参与。Orchestrator 已经在 plan 阶段把用户意图翻译成了 `{agent_type: "TRAVEL", instruction: "查明天杭州天气", parameters: {...}}`，TravelAgent 只需要执行。

### 文件变更

```
新增:
  src/main/java/.../bot/agent/travel/
    ├─ TravelAgent.java            ← 实现 AgentUnit
    ├─ TravelTaskParser.java       ← 解析 instruction → API 参数
    ├─ TravelResultFormatter.java   ← API 结果 → 用户可读文本

修改:
  ToolService.java                 ← Amap/天气/滴滴工具从 ToolRuntime 移除
  BotAutoConfiguration.java        ← 注册 TravelAgent Bean
  application.yaml                 ← Orchestrator prompt 增加 TRAVEL agent
  OrchestratorAgentImpl.java       ← PLAN_PROMPT 增加 TravelAgent 路由规则

移除:
  (不改代码) ChatAgent 自动不再能调 Amap/滴滴/天气 tool（已从 ToolRuntime 移除）
```

### 验收标准
- "明天杭州天气" → Orchestrator plan 路由到 TRAVEL → 返回正确天气
- "从国贸到西单怎么走" → Orchestrator plan 路由到 TRAVEL → 返回路线
- "帮我叫个车去机场" → Orchestrator plan 路由到 TRAVEL → 返回估价

---

## 五、Phase 3：拆出 BrowserAgent

**目标**：把 Chrome MCP 工具从 ChatAgent 的 tool loop 里移出，成为独立 Agent。

### 为什么 BrowserAgent 必须独立

| | 其他所有工具 | BrowserAgent |
|---|---|---|
| 单次执行时长 | < 3s | 可达 30s |
| 超时策略 | 快速失败重试 | 阶段性进度回调 |
| 状态 | 无状态 | 有状态（Chrome 进程、tab、cookie） |
| 模型需求 | 纯文本 | 需要视觉模型（截图理解） |
| 安全边界 | 低风险 | 高风险（可操作任意网页） |

如果 BrowserAgent 不独立，ChatAgent 的工具循环里会出现一个"慢工具"拖垮整个对话——用户发消息后 30 秒才有反应。

### BrowserAgent 设计

```java
@Component
public class BrowserAgent implements AgentUnit {

    private final BrowserMcpClient mcpClient;       // 已有
    private final BrowserSecurityPolicy securityPolicy;  // 已有
    private final BrowserAuditLogger auditLogger;      // 已有

    @Override public String getName() { return "BROWSER"; }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability("BROWSER", "浏览器操作",
            "打开网页、填写表单、截图、提取页面内容等浏览器自动化操作");
    }

    @Override
    public AgentResult execute(AgentTask task) {
        // 复用现有 BrowserTools 的 MCP 调用逻辑
        // 不加 LLM tool-calling：Orchestrator 已经给出了明确的浏览器操作指令
        // 加上进度回调：每完成一步通知 MessageHandler（"正在打开网页..."）
        // 加上超时和取消：30s 超时，用户可中断
    }
}
```

### 关键设计：进度回调

```java
// AgentTask 增加可选的进度回调
public interface AgentUnit {
    AgentResult execute(AgentTask task);

    // Phase 3 新增默认方法
    default AgentResult execute(AgentTask task, Consumer<String> onProgress) {
        return execute(task);  // 默认忽略进度
    }
}

// BrowserAgent 覆盖
@Override
public AgentResult execute(AgentTask task, Consumer<String> onProgress) {
    onProgress.accept("正在打开网页...");
    mcpClient.navigate(url);
    onProgress.accept("网页已加载，正在提取内容...");
    String content = mcpClient.extractText();
    onProgress.accept("内容提取完成");
    return AgentResult.text(content);
}
```

### 文件变更

```
新增:
  src/main/java/.../bot/agent/browser/
    ├─ BrowserAgent.java           ← 实现 AgentUnit，封装现有 BrowserTools 逻辑
    ├─ BrowserTaskParser.java      ← 解析 instruction → MCP 调用序列

修改:
  ToolService.java                 ← browser 工具从 ToolRuntime 移除
  BotAutoConfiguration.java        ← 注册 BrowserAgent Bean
  AgentUnit.java                   ← 增加带进度回调的 execute() 默认方法
  MessageRouter.java               ← 执行 loop 中按 Agent 类型区分超时策略
  OrchestratorAgentImpl.java       ← PLAN_PROMPT 增加 BROWSER agent 路由规则

保留（不删除）:
  BrowserTools.java                ← 代码保留，只是不再注册为 ProjectTool
  browser/ 目录下的其他类           ← 全部保留，BrowserAgent 内部复用
```

### 验收标准
- "帮我在携程搜一下杭州的酒店" → Orchestrator 路由到 BROWSER → 返回页面内容
- 浏览器操作进行中时，用户可以收到进度提示
- 超过 30s 自动超时，不影响其他消息
- 敏感域名（银行、支付）请求用户确认

---

## 六、Phase 4：IntentRouter 替代 Orchestrator 的简单路由

### 当前问题

Orchestrator 每次 plan 都要调一次 LLM（DeepSeek v4-pro），哪怕用户说的是"你好"或者"今天天气怎么样"。而实际上 80%+ 的消息不需要 LLM 来规划。

### 方案

在 OrchestratorAgent 前面加一层 IntentRouter，只有复杂请求才走完整 plan 流程：

```java
public class IntentRouter {

    /**
     * L1: 简单寒暄 → 直接 ChatAgent
     * L2: 单领域明确 → 直接路由到对应 Agent
     * L3: 模糊/跨领域 → 走完整 Orchestrator plan
     */
    public RoutingResult route(String text) {
        // L1: 规则匹配（关键词）
        if (isChitchat(text)) return directTo("CHAT");

        // L2: 意图分类（规则+简单分类模型，不调 Orchestrator LLM）
        String intent = classifyByRules(text);
        return switch (intent) {
            case "weather", "navigation", "taxi"  → directTo("TRAVEL");
            case "browser", "webpage"             → directTo("BROWSER");
            case "draw", "generate_image"         → directTo("IMAGE_GEN");
            case "speak", "read_aloud"            → directTo("SPEECH_GEN");
            default → needsOrchestration();  // L3: 调 Orchestrator
        };
    }
}
```

### 效果

```
简单对话 "你好"：        Phase 3之前: 2次LLM → Phase 4: 1次LLM（省 50%）
单领域 "天气怎么样"：     Phase 3之前: 2次LLM → Phase 4: 1次LLM（省 50%）
复杂请求 "搜酒店+记账"：   Phase 3之前: 3+次LLM → Phase 4: 3+次LLM（不变）
```

### 文件变更

```
新增:
  src/main/java/.../bot/router/IntentRouter.java

修改:
  MessageRouter.java               ← route() 方法开头加 IntentRouter 判断
```

---

## 七、Phase 5：AgentBus 直连通信

### 什么时候用

TravelAgent 需要 BrowserAgent 帮忙：
- TravelAgent 发现航班信息在携程网页上 → 委托 BrowserAgent 打开网页提取
- TravelAgent 规划完行程后 → 委托 AutomationAgent 设置出行提醒

但聊天场景里，TravelAgent 不需要聊天——所以最常用的委托其实是 Agent → AutomationAgent（设置提醒）和 Agent → BrowserAgent（深度网页操作）。

### 最简单的实现

```java
@Component
public class AgentBus {
    private final AgentRegistry registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 串行委托：A 委托 B，等 B 返回结果
     */
    public AgentResult delegate(String agentName, AgentTask task) {
        AgentUnit target = registry.get(agentName);
        return target.execute(task);
    }

    /**
     * 并行委托：A 同时委托 B 和 C，取最慢的结果
     */
    public Map<String, AgentResult> delegateParallel(Map<String, AgentTask> tasks) {
        return tasks.entrySet().stream()
            .parallel()
            .collect(toMap(Map.Entry::getKey, e -> delegate(e.getKey(), e.getValue())));
    }
}
```

### Agent 如何声明自己可能需要委托

```java
// TravelAgent 的 execute() 里
if (needsBrowserSearch(instruction)) {
    AgentResult browserResult = agentBus.delegate("BROWSER",
        AgentTask.of("BROWSER", "在携程搜索" + destination + "的酒店，价格" + budget));
    return aggregate(myWeatherResult, myRouteResult, browserResult);
}
```

### 文件变更

```
新增:
  src/main/java/.../bot/agent/AgentBus.java

修改:
  BotAutoConfiguration.java        ← 注册 AgentBus Bean
  TravelAgent.java                 ← 接入 AgentBus（第一个用它的 Agent）
```

---

## 八、实施顺序总览

| Phase | 内容 | 新增文件 | 修改文件 | 风险 |
|-------|------|---------|---------|------|
| **1** | 拆文件（内部类→独立文件） | ~15 | 2 | 低（纯移动） |
| **2** | 拆出 TravelAgent | 3 | 4 | 中（拆分 tool 注册） |
| **3** | 拆出 BrowserAgent | 2 | 5 | 中（独立 Chrome 进程管理） |
| **4** | IntentRouter 轻量路由 | 1 | 1 | 低（加一层判断） |
| **5** | AgentBus 直连通信 | 1 | 2 | 低（加一个工具类） |

---

## 九、不变的东西

以下模块在整个拆分过程中**完全不碰**：

- `MessageHandler.java` — 消息接收+附件下载+图片压缩+回复分发
- `DocumentService.java` — 文件解析（docx/pdf/txt/mp3）
- `VoiceService.java` — STT 语音识别
- `AiService.java` — AI 模型客户端（`OpenAiCompatibleClient`、`OpenAiImageGenClient`、`Qwen3TtsFlashClient`）
- `OllamaService.java` — 本地模型（如有）
- 除 Amap/滴滴/浏览器外的所有工具类 — `AutomationTools`、`LocalFileTools`、`MotouTool`、`SkillTools` 等
- `OrchestratorAgentImpl.java` 的 plan/reflect 核心逻辑 — 只改 prompt 文本，不改结构
- `MessageRouter.java` 的执行循环 — 只加超时区分，不改核心流程

---

## 十、最终架构

```
                        微信(iLink)             网页(HTTP)
                            │                      │
                      MessageHandler          ChatController
                            │                      │
                            └──────────┬───────────┘
                                       │
                                  IntentRouter          ← Phase 4
                                  (L1/L2/L3路由)
                                       │
                         ┌─────────────┼─────────────┐
                         │             │             │
                    L1/L2直接路由   L3走Orchestrator
                         │             │             │
                         │    ┌────────▼────────┐    │
                         │    │  Orchestrator    │    │
                         │    │  plan → execute  │    │
                         │    │    → reflect     │    │
                         │    └────────┬────────┘    │
                         │             │             │
                    ┌────▼────┬────────┼────────┬────▼────┐
                    │         │        │        │         │
                 ChatAgent  Travel  Browser  ImageGen  Speech
                 (纯对话)   Agent    Agent    Agent    Agent
                 +8轻工具   (Amap+  (Chrome  (文生图)  (TTS)
                            DiDi+    MCP)
                            天气)
                    │         │        │        │         │
                    └─────────┴────────┴────────┴─────────┘
                                       │
                                  AgentBus              ← Phase 5
                              (Agent间直连委托)
```

ChatAgent 保留 8 个轻量工具类别（worldtime、automation、localfiles、motou、skill、search、webparse、web_content），通过 Spring AI tool-calling 循环处理。TravelAgent 和 BrowserAgent 不经过 tool-calling——Orchestrator 直接给它们明确参数，它们调 API 返回结果。需要浏览器深入操作时，TravelAgent 通过 AgentBus 委托 BrowserAgent。
