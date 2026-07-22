# 多模型协作编排系统设计方案

## 1. 背景与目标

### 1.1 现状

当前项目已实现单模型 Agent 层：

```
用户消息 → AgentSink → MessageRouter → IntentRecognizer → routeChat/routeImageGen/routeSpeechGen → 回复
```

- 单次请求路由到单一模型处理
- 意图识别后直接分发，无多步骤推理
- 无追问能力，无结果校验
- 无 Tools/Function Calling 机制

### 1.2 目标

构建一个**多模型协作编排系统**：

1. **主模型（编排大脑）**：深度理解用户需求，能追问澄清，制定执行计划
2. **子模型（专业执行者）**：各自擅长不同领域，被主模型调度
3. **结果校验**：主模型判断子模型输出是否符合要求，决策是否继续调用或直接回复
4. **可扩展**：丝滑对接后续 Tools/MCP 功能

---

## 2. 核心理念

### 2.1 Agent 五层架构

```
┌──────────────────────────────────────┐
│          Observability & Safety       │  ← 日志、监控、护栏
├──────────────────────────────────────┤
│          Orchestration (编排层)       │  ← 主模型：思考→规划→调度→校验
├──────────────────────────────────────┤
│          Specialized Agents (执行层)  │  ← 子模型：对话/生图/语音/Tools
├──────────────────────────────────────┤
│          Tools (工具层)               │  ← 可扩展工具注册与调用
├──────────────────────────────────────┤
│          Memory (记忆层)              │  ← 对话记忆 + 任务上下文
└──────────────────────────────────────┘
```

### 2.2 编排模式选型：Orchestrator-Worker

选择 **Orchestrator-Worker（编排者-工作者）** 模式，理由：

| 模式 | 适用场景 | 本项目匹配度 |
|------|---------|------------|
| **Orchestrator-Worker** | 主模型动态分解任务，调度专业子模型 | 高 - 需要一个大脑协调多个专业模型 |
| Pipeline | 固定顺序的流水线 | 低 - 任务流程不固定 |
| Swarm | 去中心化自组织 | 低 - 需要确定性调度 |
| Debate | 多模型辩论 | 中 - 可作为校验子策略 |

### 2.3 推理模式：Plan-Execute-Reflect

主模型采用 **Plan-Execute-Reflect（规划-执行-反思）** 循环：

```
用户需求
  │
  ▼
┌──────────┐    不够清晰    ┌──────────┐
│  PLAN    │──────────────→│ 追问用户  │
│ 理解+规划 │               └──────────┘
└────┬─────┘
     │ 计划明确
     ▼
┌──────────┐
│ EXECUTE  │──→ 子模型A ──→ 子模型B ──→ ...
│ 调度执行  │
└────┬─────┘
     │
     ▼
┌──────────┐    不满意      ┌──────────┐
│ REFLECT  │──────────────→│ 重新规划  │
│ 校验结果  │               │ 或换模型  │
└────┬─────┘               └──────────┘
     │ 满意
     ▼
  最终回复
```

---

## 3. 架构设计

### 3.1 整体架构图

```
                          ┌──────────────────────────────┐
                          │        AgentSink (入口)       │
                          │  接收消息、多媒体预处理        │
                          └─────────────┬────────────────┘
                                        │
                                        ▼
                          ┌──────────────────────────────┐
                          │    OrchestratorAgent (主模型)  │
                          │                              │
                          │  ┌────────────────────────┐  │
                          │  │ TaskPlanner (规划器)    │  │
                          │  │ - 意图深度理解          │  │
                          │  │ - 追问判断              │  │
                          │  │ - 任务分解              │  │
                          │  └────────┬───────────────┘  │
                          │           │                   │
                          │  ┌────────▼───────────────┐  │
                          │  │ AgentDispatcher (调度器) │  │
                          │  │ - 子模型选择            │  │
                          │  │ - 上下文组装            │  │
                          │  │ - 并发/串行调度         │  │
                          │  └────────┬───────────────┘  │
                          │           │                   │
                          │  ┌────────▼───────────────┐  │
                          │  │ ResultEvaluator (校验器) │  │
                          │  │ - 输出质量判断          │  │
                          │  │ - 重试/换模型决策       │  │
                          │  │ - 最终回复合成          │  │
                          │  └────────────────────────┘  │
                          └──────────┬───────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│  ChatAgent       │   │  ImageGenAgent   │   │  SpeechAgent     │
│  (对话子模型)     │   │  (生图子模型)     │   │  (语音子模型)     │
│  model: qwen-vl  │   │  model: wan2.7   │   │  model: qwen-tts │
└──────────────────┘   └──────────────────┘   └──────────────────┘
         │                      │                      │
         └──────────────────────┼──────────────────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 ▼                 ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ Future:          │ │ Future:          │ │ Future:          │
│ CodeAgent        │ │ SearchAgent      │ │ DataAnalysisAgent│
└──────────────────┘ └──────────────────┘ └──────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                     Tool System (工具系统)                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │ToolRegistry│ │MCPClient │ │WebSearch │ │CodeExecutor │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                   Memory System (记忆系统)                     │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │Conversation  │ │TaskScratchpad│ │OrchestrationHistory  │  │
│  │Memory (已有) │ │(任务草稿本)   │ │(编排决策记录)         │  │
│  └──────────────┘ └──────────────┘ └──────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 核心接口设计

#### 3.2.1 OrchestratorAgent - 主编排代理

```java
package com.youkeda.project.wechatproject.agent.orchestration;

public interface OrchestratorAgent {

    /**
     * 处理用户请求，返回编排结果
     * @param request 用户请求上下文
     * @return 编排执行结果
     */
    OrchestrationResult process(UserRequest request);

    /**
     * 是否需要对用户追问
     */
    boolean needsClarification(String userInput, List<ChatRequest.Message> history);

    /**
     * 生成追问问题
     */
    String generateFollowUpQuestion(String userInput, List<ChatRequest.Message> history);
}
```

#### 3.2.2 AgentUnit - 子模型统一接口

```java
package com.youkeda.project.wechatproject.agent.orchestration;

public interface AgentUnit {

    /** 子模型唯一标识 */
    String getName();

    /** 子模型能力描述（供主模型选择时参考） */
    AgentCapability getCapability();

    /**
     * 执行任务
     * @param task 主模型下发的任务
     * @return 执行结果
     */
    AgentResult execute(AgentTask task);
}
```

#### 3.2.3 Tool - 工具统一接口（为后续 Tools 预留）

```java
package com.youkeda.project.wechatproject.agent.tool;

public interface Tool {

    /** 工具名称（动词短语，如 "search_web"） */
    String name();

    /** 工具描述（帮助主模型理解何时调用） */
    String description();

    /** 输入参数 Schema（JSON Schema 格式） */
    ToolSchema inputSchema();

    /** 输出 Schema（可选，用于结构化输出校验） */
    default ToolSchema outputSchema() { return null; }

    /** 执行工具 */
    ToolResult execute(ToolContext context);

    /** 工具属性标注 */
    default ToolAnnotations annotations() { return ToolAnnotations.DEFAULT; }
}
```

### 3.3 数据模型

#### OrchestrationResult - 编排结果

```java
public class OrchestrationResult {
    private OrchestrationStatus status;       // COMPLETED / NEEDS_CLARIFICATION / FAILED
    private ModelReply finalReply;            // 最终回复 (已有类型)
    private String clarificationQuestion;     // 追问问题（if NEEDS_CLARIFICATION）
    private List<SubTaskExecution> executions; // 子任务执行记录（用于日志和调试）
    private String reasoning;                 // 主模型思考过程（可观测性）
}
```

#### AgentTask - 子任务定义

```java
public class AgentTask {
    private String taskId;                    // 任务唯一ID
    private String instruction;               // 自然语言指令
    private String agentType;                 // 目标子模型类型 (CHAT / IMAGE_GEN / SPEECH / ...)
    private Map<String, Object> parameters;   // 结构化参数
    private int maxRetries = 3;              // 最大重试次数
    private TaskPriority priority;           // HIGH / MEDIUM / LOW
    private List<String> dependsOn;          // 依赖的前置任务ID（用于并行调度）
}
```

#### AgentResult - 子任务结果

```java
public class AgentResult {
    private String taskId;
    private AgentResultStatus status;        // SUCCESS / FAILED / PARTIAL
    private Object output;                   // 结构化输出
    private String rawOutput;                // 原始文本输出
    private long latencyMs;                  // 执行耗时
    private int retryCount;                  // 重试次数
    private String errorMessage;             // 错误信息
}
```

---

## 4. 核心流程

### 4.1 正常流程（无追问）

```
用户: "帮我画一张赛博朋克风格的猫，然后再给它配一段文字描述"
  │
  ▼
┌─ OrchestratorAgent.process() ─────────────────────────────┐
│                                                            │
│  STEP 1: PLAN（规划）                                       │
│  主模型分析:                                                │
│  - 意图: 生图 + 文本生成                                    │
│  - 分解任务:                                                │
│    Task-1: 生成赛博朋克风格猫的图片 (IMAGE_GEN)              │
│    Task-2: 为这张图写一段描述文字 (CHAT)                     │
│  - Task-2 依赖 Task-1 的输出（需要知道画面内容）              │
│  - 不需要追问（信息足够清晰）                                │
│                                                            │
│  STEP 2: EXECUTE（执行）                                     │
│  Task-1 → ImageGenAgent.generate("赛博朋克风格的猫...")       │
│  结果: SUCCESS, image_bytes                                 │
│  Task-2 → ChatAgent.chat("为这张图写描述", [image_bytes])    │
│  结果: SUCCESS, "这是一只霓虹灯光下的机械猫..."              │
│                                                            │
│  STEP 3: REFLECT（校验）                                     │
│  主模型评估:                                                 │
│  - Task-1: 图片生成成功 ✓                                   │
│  - Task-2: 描述文字贴切、文笔流畅 ✓                          │
│  - 综合判断: 任务完成，组装最终回复                           │
│                                                            │
│  最终回复: [图片] + "这是一只霓虹灯光下的机械猫..."           │
└────────────────────────────────────────────────────────────┘
```

### 4.2 追问流程

```
用户: "帮我做个东西"
  │
  ▼
┌─ OrchestratorAgent.process() ─────────────────────────────┐
│  STEP 1: PLAN                                              │
│  主模型分析: 信息严重不足，无法确定意图                     │
│  决策: NEEDS_CLARIFICATION                                 │
│  生成追问: "您好！请问您想做什么呢？比如：                   │
│            - 生成一张图片                                   │
│            - 写一段文字                                     │
│            - 其他需求                                       │
│           请告诉我更多细节~"                                 │
└────────────────────────────────────────────────────────────┘
  │
  ▼
用户: "我想要一张图片，未来科技风格的城市"
  │
  ▼
┌─ OrchestratorAgent.process() ─────────────────────────────┐
│  STEP 1: PLAN                                              │
│  主模型分析: 意图清晰（生成图片），风格明确（未来科技城市）   │
│  追问判断:                                                  │
│  - 还需要确认: 尺寸偏好？数量？写实还是插画？                 │
│  决策: 信息基本足够，直接生成；若有疑问可在结果后补充询问      │
│  → 进入 EXECUTE                                            │
│  ...                                                       │
└────────────────────────────────────────────────────────────┘
```

### 4.3 校验失败重试流程

```
用户: "写一首七言绝句，主题是春天"
  │
  ▼
┌─ OrchestratorAgent.process() ─────────────────────────────┐
│  STEP 1: PLAN                                              │
│  Task-1: 生成七言绝句 (CHAT)                                │
│                                                            │
│  STEP 2: EXECUTE                                           │
│  Task-1 → ChatAgent 返回一首五言诗（模型搞错了）             │
│                                                            │
│  STEP 3: REFLECT                                           │
│  主模型评估:                                                │
│  - 格式检查: 要求七言，实际五言 ✗                            │
│  - 主题检查: 春天 ✓                                        │
│  - 决策: 重试，强调"必须是七言绝句，每句7个字"               │
│                                                            │
│  RETRY: Task-1 → ChatAgent("必须七言，每句7字，主题春天")    │
│  结果: 七言绝句 ✓                                           │
│  → 最终回复                                                │
└────────────────────────────────────────────────────────────┘
```

---

## 5. 与现有代码的对接方案

### 5.1 演进路径（兼容现有功能）

```
Phase 1 (当前):  Phase 2 (本方案):           Phase 3 (未来):
单模型路由       Orchestrator-Worker         Full Agent + Tools

AgentSink        AgentSink (不变)            AgentSink (不变)
  │                │                            │
MessageRouter    MessageRouter (重构)         MessageRouter
  │                │                            │
IntentRecognizer OrchestratorAgent            OrchestratorAgent
  │                │                            │
routeXxx()       ├─ TaskPlanner               ├─ TaskPlanner
                   ├─ AgentDispatcher           ├─ AgentDispatcher
                   ├─ ResultEvaluator           ├─ ResultEvaluator
                   └─ AgentUnit[]               ├─ AgentUnit[]
                                                 └─ ToolRegistry
                                                    ├─ WebSearchTool
                                                    ├─ CodeExecutorTool
                                                    └─ McpToolAdapter
```

### 5.2 现有代码改造点

| 现有类 | 改造方式 | 说明 |
|--------|---------|------|
| `AgentSink` | **保持不变** | 入口逻辑不变，仍然负责消息接收和多媒体预处理 |
| `MessageRouter` | **重构为协调器** | `route()` 方法改为调用 `OrchestratorAgent` |
| `IntentRecognizer` | **下沉为子模块** | 意图识别不再是顶层路由逻辑，而是主模型规划阶段的一部分 |
| `AiModelClient` | **扩展接口** | 每个子模型 Agent 内部持有自己的 `AiModelClient` 实例 |
| `ImageGenClient` | **封装为 AgentUnit** | 包装为 `ImageGenAgent implements AgentUnit` |
| `ConversationMemory` | **保持不变** | 记忆层继续服务，增加 `TaskScratchpad` 作为补充 |
| `ModelReply` | **保持不变** | 最终回复格式不变 |

### 5.3 MessageRouter 改造示例

```java
// 改造前 (当前)
public ModelReply route(String userId, String text, List<String> imageBase64Urls) {
    List<ChatRequest.Message> history = memory.getHistory(userId);
    IntentResult intent = intentRecognizer.recognize(text, history);
    switch (intent.getType()) {
        case CHAT -> { return routeChat(userId, text, imageBase64Urls, history); }
        case IMAGE_GEN -> { return routeImageGen(userId, text, history, intent); }
        case SPEECH_GEN -> { return routeSpeechGen(userId, text, history, intent); }
    }
}

// 改造后
public ModelReply route(String userId, String text, List<String> imageBase64Urls) {
    List<ChatRequest.Message> history = memory.getHistory(userId);

    // 构建用户请求上下文
    UserRequest request = UserRequest.builder()
        .userId(userId)
        .text(text)
        .images(imageBase64Urls)
        .history(history)
        .build();

    // 主编排代理处理
    OrchestrationResult result = orchestrator.process(request);

    // 处理追问情况
    if (result.getStatus() == OrchestrationStatus.NEEDS_CLARIFICATION) {
        return ModelReply.text(result.getClarificationQuestion());
    }

    // 更新记忆
    memory.append(userId, text, result.getFinalReply().getTextContent());

    return result.getFinalReply();
}
```

---

## 6. 子模型扩展机制

### 6.1 AgentUnit 注册

使用 Spring 的自动发现机制，新的子模型只需实现 `AgentUnit` 接口并注册为 Bean：

```java
@Component
public class ChatAgent implements AgentUnit {

    private final AiModelClient chatClient;

    @Override
    public String getName() { return "chat-agent"; }

    @Override
    public AgentCapability getCapability() {
        return AgentCapability.builder()
            .name("对话生成")
            .description("擅长自然语言对话、文本创作、翻译、摘要、分析")
            .strengths(List.of("文本生成", "翻译", "创作", "代码"))
            .supportedInputTypes(List.of("text", "image"))
            .outputType("text")
            .model("qwen3-vl-plus")
            .build();
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        String response = chatClient.chat(
            task.getInstruction(),
            task.getImageUrls(),
            task.getContextHistory()
        );
        return AgentResult.success(task.getTaskId(), response);
    }
}
```

### 6.2 子模型能力声明

```java
public class AgentCapability {
    private String name;                    // 名称
    private String description;             // 能力描述（供主模型选型参考）
    private List<String> strengths;         // 擅长领域
    private List<String> supportedInputTypes; // 支持的输入类型 [text, image, voice, file]
    private String outputType;              // 输出类型 [text, image, voice]
    private String model;                   // 使用的模型名称
    private double costPerToken;            // 成本（用于成本优化决策）
    private int avgLatencyMs;               // 平均延迟（用于延迟优化决策）
}
```

### 6.3 注册新子模型的步骤

```
1. 实现 AgentUnit 接口
2. 添加 @Component 注解
3. 在 AgentCapability 中声明能力
4. Spring 自动发现并注册到 AgentRegistry
5. 主模型通过 AgentRegistry 发现可用子模型
```

---

## 7. Tools 系统设计（为后续预留）

### 7.1 设计原则

- **零耦合**：Tools 系统与 Agent 编排系统独立，通过接口对接
- **渐进发现**：工具少于20个时全部列出，超过后支持搜索发现
- **MCP 兼容**：Tool 接口设计对齐 MCP 规范，后续可接入 MCP 生态
- **安全分级**：区分只读/可逆/不可逆操作，不同级别不同授权流程

### 7.2 Tool 接口

```java
package com.youkeda.project.wechatproject.agent.tool;

public interface Tool {
    String name();              // e.g., "web_search", "code_execute"
    String description();       // 自然语言描述，帮助主模型判断何时调用
    ToolSchema inputSchema();   // JSON Schema 格式的输入参数定义
    ToolResult execute(ToolContext context);
    ToolAnnotations annotations(); // readOnly, destructive, idempotent 标注
}

// 工具标注
public record ToolAnnotations(
    boolean readOnly,       // 是否只读（无副作用）
    boolean destructive,    // 是否破坏性操作
    boolean idempotent      // 是否幂等
) {
    public static final ToolAnnotations DEFAULT = new ToolAnnotations(true, false, true);
}

// 工具上下文
public class ToolContext {
    private String userId;
    private Map<String, Object> arguments;  // 解析后的参数
    private ToolCallAuthorization auth;     // 授权信息
}

// 工具结果
public class ToolResult {
    private boolean success;
    private Object content;     // 结构化内容
    private String errorMessage;
}
```

### 7.3 ToolRegistry - 工具注册中心

```java
@Service
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 生成主模型的 Tool Use 提示词片段
     * 主模型通过这段提示词了解可用工具及其用法
     */
    public String generateToolPrompt() {
        // 生成 JSON 格式的工具列表描述
        // 符合 OpenAI Function Calling 格式，兼容 MCP tools/list
    }
}
```

### 7.4 未来 MCP 对接

```
当前 Tool 接口                MCP 对接方案
─────────────                ────────────
Tool.name()         ←→      tools/list → name
Tool.description()  ←→      tools/list → description
Tool.inputSchema()  ←→      tools/list → inputSchema (JSON Schema)
Tool.execute()      ←→      tools/call → result
ToolAnnotations     ←→      tools/list → annotations
```

后续引入 MCP Client 时，可通过适配器模式将 MCP Server 暴露的工具转换为本地 `Tool` 接口：

```java
public class McpToolAdapter implements Tool {
    private final McpClient mcpClient;
    private final ToolInfo remoteToolInfo;

    @Override
    public String name() { return remoteToolInfo.getName(); }

    @Override
    public ToolResult execute(ToolContext context) {
        // 转换为 MCP tools/call 请求
        CallToolResult mcpResult = mcpClient.callTool(name(), context.getArguments());
        // 转换为本地 ToolResult
        return ToolResult.fromMcp(mcpResult);
    }
}
```

---

## 8. 追问机制（Human-in-the-Loop）

### 8.1 追问触发条件

主模型在 PLAN 阶段判断是否需要追问，触发条件：

1. **意图不明确**：无法确定用户想做什么
2. **关键信息缺失**：确定了意图但缺少必要参数（如生图缺少主题描述）
3. **多义性**：用户指令存在多种解读
4. **安全边界**：用户请求可能涉及不安全内容，需要确认
5. **高风险确认**：操作不可逆（如删除、发布）需要用户确认

### 8.2 追问策略

```
追问等级:
  L1: 开放式追问 - "请问您想做什么呢？"
  L2: 引导式追问 - "您是想生成图片还是写文字？"
  L3: 精确追问   - "您要的图片尺寸是 1024x1024 还是 512x512？"
  L4: 确认式追问 - "即将生成4张图片，确认吗？"
```

### 8.3 实现方式

```java
// 在 OrchestratorAgent 中
public OrchestrationResult process(UserRequest request) {
    // PLAN 阶段
    PlanResult plan = planner.plan(request);

    if (plan.needsClarification()) {
        return OrchestrationResult.needsClarification(
            plan.getClarificationQuestion(),
            plan.getSuggestedOptions()  // 可选的引导选项
        );
    }

    // EXECUTE + REFLECT ...
}
```

---

## 9. 上下文管理

### 9.1 三层记忆模型

```
┌──────────────────────────────────────────────┐
│  Layer 1: ConversationMemory (已有)          │
│  - 用户-助手对话历史                          │
│  - TTL + 最大轮次控制                         │
│  - 支持追加和清除                             │
├──────────────────────────────────────────────┤
│  Layer 2: TaskScratchpad (新增)              │
│  - 当前编排任务的草稿本                        │
│  - Plan → Sub-tasks → Results 链路记录        │
│  - 只在单次编排生命周期内有效                   │
├──────────────────────────────────────────────┤
│  Layer 3: OrchestrationHistory (新增-可选)    │
│  - 编排决策历史记录                            │
│  - 主模型选择子模型的原因                      │
│  - 校验通过/失败的原因                         │
│  - 用于可观测性和调试                          │
└──────────────────────────────────────────────┘
```

### 9.2 上下文传递原则

- 子模型获得**精简上下文**（仅与当前任务相关）
- 主模型保持**全局视图**（整体计划 + 各子模型结果摘要）
- 结构化 Handoff（Typed Schema，不是原始文本拼接）
- Token 预算管理（子任务上下文控制在 ~10k tokens）

---

## 10. 配置设计

### 10.1 application.properties 扩展

```properties
# ========== 已有配置 (保持不变) ==========
agent.ai.enabled=true
agent.ai.api-key=xxx
agent.ai.api-url=https://dashscope.aliyuncs.com/compatible-mode/v1
agent.ai.model=qwen3-vl-plus
agent.ai.system-prompt=...

# ========== 新增: 编排器配置 ==========
agent.orchestrator.enabled=true
agent.orchestrator.model=qwen3-vl-plus          # 主模型（可与通用对话模型相同或不同）
agent.orchestrator.max-plan-steps=10            # 最大计划步骤数
agent.orchestrator.max-retries=3                # 子任务最大重试次数
agent.orchestrator.reflection-enabled=true      # 是否启用反思校验
agent.orchestrator.clarification-enabled=true   # 是否启用追问

# ========== 新增: 子模型配置 ==========
agent.workers.chat.model=qwen3-vl-plus
agent.workers.chat.temperature=0.7
agent.workers.image-gen.model=wan2.7-image-pro
agent.workers.image-gen.size=1024*1024
agent.workers.speech.tts.model=qwen-audio-3.0-tts-flash
agent.workers.speech.tts.voice=longanhuan_v3.6

# ========== 新增: 工具系统配置 ==========
agent.tools.enabled=false                        # Phase 3 启用
agent.tools.max-tools-per-turn=10               # 每轮最多暴露的工具数
agent.tools.discovery-strategy=progressive       # flat | progressive
```

---

## 11. 包结构规划

```
com.youkeda.project.wechatproject.agent
├── AgentProperties.java              (已有，扩展)
├── AgentAutoConfiguration.java       (已有，扩展)
├── AgentSink.java                    (已有，不改)
├── AiModelClient.java               (已有，不改)
├── ChatRequest.java                  (已有，不改)
├── ChatResponse.java                 (已有，不改)
├── ConversationMemory.java          (已有，不改)
├── InMemoryConversationMemory.java  (已有，不改)
├── ImageGenClient.java              (已有，不改)
├── DashScopeImageGenClient.java     (已有，不改)
│
├── orchestration/                    (新增 - 编排层)
│   ├── OrchestratorAgent.java       接口
│   ├── OrchestratorAgentImpl.java   主模型编排实现
│   ├── OrchestratorProperties.java  配置
│   ├── TaskPlanner.java             规划器接口
│   ├── LlmTaskPlanner.java          LLM驱动的规划器
│   ├── AgentDispatcher.java         调度器
│   ├── ResultEvaluator.java         校验器
│   ├── AgentUnit.java               子模型统一接口
│   ├── AgentRegistry.java           子模型注册中心
│   ├── AgentCapability.java         能力声明模型
│   ├── AgentTask.java               任务模型
│   ├── AgentResult.java             结果模型
│   ├── OrchestrationResult.java     编排结果模型
│   ├── TaskScratchpad.java          任务草稿本
│   │
│   ├── workers/                     内置子模型实现
│   │   ├── ChatAgent.java
│   │   ├── ImageGenAgent.java
│   │   └── SpeechAgent.java
│   └── config/
│       └── OrchestrationAutoConfiguration.java
│
├── tool/                             (新增 - 工具系统，Phase 3)
│   ├── Tool.java                    工具接口
│   ├── ToolSchema.java              参数Schema
│   ├── ToolContext.java             工具调用上下文
│   ├── ToolResult.java              工具执行结果
│   ├── ToolAnnotations.java         工具标注
│   ├── ToolRegistry.java            工具注册中心
│   └── mcp/                         MCP适配层
│       ├── McpClient.java
│       └── McpToolAdapter.java
│
├── intent/                           (已有，保留作为子模块)
│   ├── IntentType.java
│   ├── IntentResult.java
│   ├── IntentRecognizer.java
│   ├── RegexIntentRecognizer.java
│   └── LlmIntentRecognizer.java
│
├── routing/                          (已有，MessageRouter重构)
│   ├── ModelReply.java
│   └── MessageRouter.java
│
└── speech/                           (已有，不改)
    ├── SpeechProperties.java
    ├── SpeechAutoConfiguration.java
    ├── SpeechToTextClient.java
    ├── TextToSpeechClient.java
    ├── FunAsrSttClient.java
    ├── Qwen3TtsFlashClient.java
    ├── TtsResult.java
    └── AudioConverter.java
```

---

## 12. 实施路线图

### Phase 1: 基础编排（当前 → 2周）
- [ ] 新增 `orchestration/` 包，定义核心接口
- [ ] 实现 `OrchestratorAgentImpl`（Plan-Execute-Reflect 循环）
- [ ] 将现有 Chat/ImageGen/Speech 封装为 `AgentUnit`
- [ ] `MessageRouter` 重构为调用 `OrchestratorAgent`
- [ ] 实现追问机制

### Phase 2: 多模型协作增强
- [ ] 实现并行子任务调度
- [ ] 实现依赖感知的任务编排（Task A → Task B → Task C）
- [ ] 增强 `ResultEvaluator`（格式校验、内容质量判断）
- [ ] 增加 `OrchestrationHistory` 日志

### Phase 3: Tools 系统
- [ ] 实现 `Tool` 接口和 `ToolRegistry`
- [ ] 主模型支持 Tool Use（集成 Function Calling）
- [ ] 内置基础工具（WebSearch、Calculator 等）
- [ ] MCP Client 适配器

### Phase 4: 高级特性
- [ ] MCP Server 支持（暴露项目能力为 MCP Tools）
- [ ] 条件路由（根据成本/延迟动态选择模型）
- [ ] A/B 测试和模型效果对比
- [ ] 编排策略的持久化和回放

---

## 13. 关键设计决策总结

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 编排模式 | Orchestrator-Worker | 主模型动态分解任务，灵活调度子模型 |
| 推理模式 | Plan-Execute-Reflect | 规划明确、执行可控、结果可校验 |
| 追问机制 | LLM 自主判断 + 追问等级 | 避免硬编码规则，主模型根据上下文灵活追问 |
| 上下文管理 | 三层记忆 + 结构化和手 | 隔离子模型上下文，主模型保持全局视图 |
| 子模型扩展 | AgentUnit 接口 + Spring Bean | 零侵入扩展，Spring 自动发现 |
| 工具系统 | 独立 Tool 接口 + MCP 兼容 | 与编排层解耦，未来可接入 MCP 生态 |
| 主模型选择 | 复用现有 AiModelClient | 不增加新的模型客户端，降低复杂度 |

---

## 14. 参考资料

- Anthropic - Building Effective Agents (2024)
- ReAct: Synergizing Reasoning and Acting in Language Models (2022)
- Plan-and-Execute Agents (LangGraph, 2024)
- Model Context Protocol Specification (MCP) - 2025-11-25
- OpenAI Agents SDK - Orchestrator Pattern
- CrewAI - Multi-Agent Orchestration Framework
