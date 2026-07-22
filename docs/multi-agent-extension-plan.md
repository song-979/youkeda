# 多Agent协作、Tools调用、Skills拓展方案

## 一、项目现状分析

### 1.1 当前架构

```
WeChat (iLink SDK) → ILink Layer → Agent Layer → External AI APIs
                      (ilink包)      (agent包)
```

- **iLink层**：WeChat iLink SDK集成（连接、登录、消息桥接、QR码控制器）
- **Agent层**：AI Agent逻辑（文本聊天、图片生成、意图识别、路由、记忆）

### 1.2 现有能力

| 能力 | 实现方式 | 状态 |
|------|---------|------|
| 文本对话 | OpenAiCompatibleClient + SSE流式 | 已完成 |
| 图片理解 | 多模态Vision API (qwen3-vl-plus) | 已完成 |
| 文生图 | DashScope wan2.7-image-pro 异步提交+轮询 | 已完成 |
| 意图识别 | LlmIntentRecognizer + RegexIntentRecognizer 双层回退 | 已完成 |
| 对话记忆 | InMemoryConversationMemory (ConcurrentHashMap + TTL) | 已完成 |
| 打字指示器 | "对方正在输入..." | 已完成 |

### 1.3 技术栈

- Java 21 + Spring Boot 3.2.5 + Maven
- OpenAI兼容API协议（非流式用RestTemplate，流式用HttpURLConnection+SSE）
- 无数据库依赖（全内存状态）
- Auto-Configuration模式，基于Spring Bean装配

### 1.4 适合拓展的原因

1. **已有的Router模式** — `MessageRouter` 已经是一个中心调度器，天然可以演进为 Agent Orchestrator
2. **已有的Intent识别** — `LlmIntentRecognizer` 的思路和 Function Calling 一脉相承，可以平滑升级
3. **OpenAI兼容协议** — `OpenAiCompatibleClient` 已经支持多模态，加入 Function Calling 只需扩展 `ChatRequest`
4. **Auto-Configuration架构** — 基于Spring Boot自动装配，新增能力只需加新的AutoConfiguration，扩展性极好
5. **清晰的分层** — iLink层只负责微信通信，Agent层只负责AI逻辑，新增能力不会破坏现有结构

---

## 二、拓展方案总览

按 **三个递进阶段** 拓展，每个阶段都有独立价值，不需要全部完成才能上线。

### 2.1 阶段一：Tools/Function Calling 框架

**核心思路**：让Agent能主动调用外部工具，而不只是被动回复文本/生成图片。

#### 新增模块结构

```
agent/tools/
├── ToolDefinition.java          # 工具定义（name, description, parameters JSON Schema）
├── ToolExecutor.java            # 工具执行器接口
├── ToolRegistry.java            # 工具注册中心（自动发现Spring Bean）
├── ToolCallingClient.java       # 支持function calling的API客户端（扩展OpenAiCompatibleClient）
├── ReActLoop.java               # ReAct循环：思考→调用工具→观察结果→再思考→最终回复
└── builtin/
    ├── WebSearchTool.java        # 联网搜索
    ├── CalculatorTool.java       # 数学计算
    ├── WeatherTool.java         # 天气查询
    ├── DateTimeTool.java        # 日期时间
    └── MemorySearchTool.java    # 搜索历史对话
```

#### 核心设计

**工具声明（注解驱动）**：

```java
@Tool(
    name = "web_search",
    description = "搜索互联网获取实时信息",
    parameters = {
        @Param(name = "query", type = "string", description = "搜索关键词", required = true),
        @Param(name = "max_results", type = "integer", description = "最大结果数", required = false)
    }
)
public class WebSearchTool implements ToolExecutor {
    @Override
    public ToolResult execute(Map<String, Object> params) {
        // 执行搜索逻辑
    }
}
```

**ReAct循环流程**：

```
用户消息 → 构建带tools的请求 → 调用LLM
  ├─ LLM返回文本 → 直接回复用户（结束）
  └─ LLM返回tool_calls → 执行工具 → 结果注入消息历史 → 再次调用LLM
       └─ 最多迭代5轮 → 超限则强制要求LLM输出文本回复
```

**ChatRequest扩展**：

```java
// 在现有ChatRequest基础上新增
private List<Tool> tools;           // 可用工具列表
private String toolChoice;          // "auto" / "none" / 指定工具
// Choice中新增
private ToolCall delta;             // 流式tool_calls增量
private List<ToolCall> toolCalls;   // 非流式tool_calls
```

#### 关键技术点

- 自动扫描 `@Tool` 注解的Bean，注册到 `ToolRegistry`
- 工具定义的 `parameters` 自动转换为 OpenAI Function Calling 的 JSON Schema 格式
- `ToolCallingClient` 继承 `OpenAiCompatibleClient`，复用现有的认证、超时、多模态能力
- 工具执行结果以 `role: "tool"` 消息注入对话历史
- 支持并行工具调用（LLM一次返回多个tool_calls时并发执行）

---

### 2.2 阶段二：多Agent协作系统

**核心思路**：引入多个专业化Agent，由一个Orchestrator动态决定由谁处理、如何协作。

#### 新增模块结构

```
agent/multiagent/
├── AgentDefinition.java           # Agent定义（name, systemPrompt, tools[], model, temperature）
├── AgentRegistry.java             # Agent注册中心
├── AgentOrchestrator.java         # 多Agent编排器（增强MessageRouter）
├── AgentMessage.java              # Agent间通信消息格式
├── AgentHandoff.java              # Agent间任务移交协议
├── AgentTeam.java                 # Agent团队（预定义的协作组合）
├── strategies/
│   ├── CollaborationStrategy.java # 协作策略接口
│   ├── RoundRobinStrategy.java    # 轮询策略
│   ├── VoteStrategy.java          # 多Agent投票策略
│   ├── PipelineStrategy.java      # 流水线策略（A→B→C）
│   └── DebateStrategy.java        # 多Agent辩论+裁判策略
└── builtin/
    ├── ResearcherAgent.java        # 研究员Agent
    ├── CoderAgent.java             # 程序员Agent
    ├── TranslatorAgent.java        # 翻译Agent
    ├── CreativeWriterAgent.java    # 创意写作Agent
    └── CriticAgent.java            # 审查Agent（安全检查、质量评估）
```

#### 核心协作模式

**1. 流水线 (Pipeline)**

```
用户请求 → [Researcher] 搜集信息 → [Writer] 整理输出 → [Critic] 质量审查 → 发送给用户

适用场景：需要深度研究的复杂问题
特点：前一个Agent的输出是后一个Agent的输入，串行执行
```

**2. 辩论+裁判 (Debate+Judge)**

```
用户请求 → [Agent A] 给出方案A
          → [Agent B] 给出方案B  } 并行执行
          → [Agent C] 给出方案C
          → [Critic] 裁判打分 → 选择最佳回复

适用场景：需要多角度思考的开放式问题
特点：并行执行，裁判Agent从多个方案中择优
```

**3. 动态委托 (Dynamic Delegation)**

```
用户请求 → [Orchestrator] 分析请求 → 选择最合适的Agent → Agent独立完成 → 返回结果

适用场景：大多数日常对话（类似当前MessageRouter但更智能）
特点：Orchestrator根据Agent的能力描述匹配最合适的人选
```

**4. 链式对话 (Chain Conversation)**

```
用户请求 → [Agent A] 处理部分
          → [Agent B] 基于A的结果继续处理
          → [Agent C] 基于A+B的结果最终输出

适用场景：复杂的多步骤任务
特点：每个Agent可以看到前面所有Agent的输出
```

#### Agent间通信协议

```java
public record AgentMessage(
    String id,
    String senderAgent,          // 发送者Agent名称
    String receiverAgent,        // 接收者Agent名称
    String task,                 // 任务描述
    Map<String, Object> context, // 上下文/中间结果
    Object result,               // 执行结果
    long timestamp
) {}
```

#### Agent定义示例

```java
@AgentDefinition(
    name = "researcher",
    description = "专注于信息搜索和分析的Agent，擅长网络搜索和数据整理",
    systemPrompt = """
        你是一个专业的研究员Agent。
        你的职责：
        1. 深入分析用户的问题
        2. 使用web_search工具搜索相关信息
        3. 整理和归纳搜索结果
        4. 提供有来源支撑的分析报告
        请在回答中标注信息来源。
        """,
    tools = {WebSearchTool.class},
    model = "qwen3-vl-plus",
    temperature = 0.3
)
```

---

### 2.3 阶段三：Skills技能系统

**核心思路**：Skill = System Prompt + Tools组合 + 工作流定义，可复用、可组合、可分享。

#### 新增模块结构

```
agent/skills/
├── SkillDefinition.java          # 技能定义（name, description, prompt, tools[], workflow）
├── SkillRegistry.java            # 技能注册中心
├── SkillExecutor.java            # 技能执行器
├── SkillComposer.java            # 技能组合器（多个skill串联）
├── SkillWorkflow.java            # 工作流引擎（步骤定义+条件跳转）
├── SkillTrigger.java             # 技能触发条件（关键词、正则、Intent匹配）
├── SkillRouter.java              # 基于@技能名 语法的路由
├── UserSkillStore.java           # 用户自定义技能存储
└── builtin/
    ├── SummarizeSkill.java        # 摘要技能
    ├── TranslateSkill.java        # 翻译技能
    ├── CodeReviewSkill.java       # 代码审查技能
    ├── DailyReportSkill.java      # 日报生成技能
    ├── MindMapSkill.java          # 思维导图生成
    ├── RolePlaySkill.java         # 角色扮演技能
    ├── FactCheckSkill.java        # 事实核查技能
    └── BrainstormSkill.java       # 头脑风暴技能
```

#### Skill vs Tool 对比

| 维度 | Tool | Skill |
|------|------|-------|
| 粒度 | 原子操作 | 复合操作 |
| 复杂度 | 单一API调用 | 多步骤工作流 |
| 组合性 | 被LLM动态组合 | 预定义组合 |
| 用户感知 | 不可见（LLM内部决策） | 用户可明确调用 |
| 示例 | `web_search("天气")` | `@日报 今天的讨论` |

#### 技能工作流引擎

支持以下工作流节点类型：

```
工作流节点类型：
├── LLMNode          # 调用LLM处理
├── ToolNode         # 调用单个工具
├── AgentNode        # 委托给特定Agent
├── ConditionNode    # 条件分支（if/else）
├── LoopNode         # 循环节点
├── ParallelNode     # 并行执行
└── OutputNode       # 最终输出格式化
```

#### 技能定义示例

```yaml
# 日报生成技能工作流 (yaml格式便于存储和分享)
skill:
  name: "daily_report"
  description: "生成每日工作总结报告"
  trigger: ["日报", "日报总结", "今日总结"]

  workflow:
    - type: LLMNode
      name: "query_expansion"
      prompt: "将用户的日报请求扩展为以下三个维度的具体问题：已完成、进行中、计划"

    - type: ToolNode
      name: "search_history"
      tool: "memory_search"
      params:
        timeframe: "today"

    - type: ParallelNode
      name: "content_generation"
      branches:
        - type: LLMNode
          name: "done_section"
          prompt: "基于搜索到的对话历史，整理今日已完成的工作"
        - type: LLMNode
          name: "progress_section"
          prompt: "基于搜索到的对话历史，整理今日进行中的工作"
        - type: LLMNode
          name: "plan_section"
          prompt: "基于搜索到的对话历史，整理明日计划"

    - type: LLMNode
      name: "final_format"
      prompt: "将以下三个部分整合为一份格式化的日报"
      input: ["done_section", "progress_section", "plan_section"]
```

#### 用户交互语法

```
@技能名 参数           → 调用指定技能，如 @翻译 将这段话翻译成英文
@技能名 --help        → 查看技能详情
@技能列表              → 列出所有可用技能
/创建技能 名称 描述...  → 用户自定义新技能（通过对话式引导创建）
```

---

## 三、目标全架构图

```
┌─────────────────────────────────────────────────────────┐
│                    WeChat iLink Layer                    │
│  ILinkClient ←→ MessageBridge ←→ AgentSink              │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              Agent Orchestrator (增强后的MessageRouter)    │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐ │
│  │ Intent       │  │ Skill         │  │ Multi-Agent  │ │
│  │ Recognizer   │  │ Router        │  │ Dispatcher   │ │
│  └──────┬───────┘  └───────┬───────┘  └──────┬───────┘ │
│         │                  │                  │         │
│         ▼                  ▼                  ▼         │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Agent Registry                        │  │
│  │  Researcher│Coder│Writer│Translator│Critic│...    │  │
│  └───────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                   Tools & Skills Layer                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Tool         │  │ ReAct        │  │ Skill         │  │
│  │ Registry     │  │ Loop         │  │ Registry      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                    Memory & Storage                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Working      │  │ Episodic     │  │ Semantic      │  │
│  │ Memory       │  │ Memory       │  │ Memory (Vec)  │  │
│  │ (当前对话)    │  │ (历史对话)    │  │ (知识图谱)     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 四、推荐实施路线

### 优先级排序

| 优先级 | 阶段 | 原因 |
|--------|------|------|
| P0 | 阶段一：Tools/Function Calling | 投入产出比最高，为后续打基础，用户体验提升明显 |
| P1 | 阶段二：多Agent协作 | 需要阶段一的Tools能力支撑，架构层面的重大升级 |
| P2 | 阶段三：Skills技能系统 | 需要前两个阶段的基础设施，面向用户的高级功能层 |

### 推荐先从阶段一开始的原因

1. **改动最小，收益最大** — 主要改动集中在 `ChatRequest` 扩展 + `ToolRegistry` + `ReActLoop`，现有代码几乎不需要修改
2. **立即可用** — 做完就能让Agent具备联网搜索、计算、查天气等实际能力
3. **验证架构** — 通过阶段一验证扩展方向是否正确，再决定是否投入后续阶段
4. **为后续铺路** — 多Agent协作中每个Agent都需要Tool调用能力，Skills系统底层也是基于Tool的

### 阶段一具体实施步骤

1. **扩展ChatRequest/ChatResponse** — 增加 `tools`、`tool_choice`、`tool_calls` 字段
2. **创建Tool注解和ToolRegistry** — 注解驱动 + 自动扫描
3. **实现ReActLoop** — 思考→工具调用→观察→再思考 的主循环
4. **实现2-3个内置Tool** — web_search、calculator、datetime
5. **修改MessageRouter集成ReActLoop** — 现有chat路径改为ReAct路径
6. **测试和调优** — 确保工具调用稳定，异常处理完善
