# Context Engineering Service Design

## Goal

把主 Agent 的上下文装配逻辑从 `MessageRouter` / `OrchestratorAgentImpl` 中独立出来，做成项目内可复用的 Java Service。

这个 Service 不负责回答用户，也不直接执行工具。它只负责根据当前请求、历史、任务状态、Agent 能力和 token 预算，构造一份适合主 Agent 使用的 `ContextPackage`。

## Scope

本阶段采用方案 B：标准上下文包。

- 在当前 Spring Boot 项目内实现 Java Service，不先做 HTTP/RPC 服务。
- 主要服务对象是主编排 Agent 的 `plan` 和 `reflect`。
- 子 Agent 默认上下文隔离，只接收主 Agent 下发的 `AgentTask`。
- 子 Agent 的跨轮恢复状态通过 `TaskScratchpad` / `resumeState` 显式传递，不隐藏在子 Agent 内部长期记忆里。

## Non-goals

- 不重写现有多 Agent 执行循环。
- 不改变现有工具调用能力。
- 不做外部微服务拆分。
- 不把所有历史都塞给模型。
- 不让子 Agent 读取完整对话历史。

## Main API

新增 `ContextEngineeringService`：

```java
public interface ContextEngineeringService {
    ContextPackage build(ContextBuildRequest request);
}
```

`ContextBuildRequest` 包含：

```java
String userId;
String currentMessage;
ContextStage stage;              // PLAN, REFLECT, RESUME, SCHEDULED
List<ChatRequest.Message> recentHistory;
TaskScratchpad scratchpad;
List<AgentCapabilityView> agentCapabilities;
List<String> imageBase64Urls;
String rememberedImageSummary;
ContextBudget budget;
```

`ContextPackage` 包含：

```java
List<ChatRequest.Message> messages;
ContextRelevance relevance;
ContextBudgetReport budgetReport;
List<ContextCompressionAction> compressionActions;
```

## Relevance Model

不要只做弱相关 / 强相关二分类。内部使用更细的枚举，外部仍可折叠成轻重模式：

```java
enum ContextRelevance {
    NEW_TOPIC,
    RELATED,
    CONTINUATION,
    RESUME_TASK,
    TOOL_DEPENDENT
}
```

判断策略：

- `NEW_TOPIC`：当前请求基本不依赖历史，只需要基础上下文。
- `RELATED`：和历史主题相关，需要远期摘要和近期原文。
- `CONTINUATION`：明显接着上一轮说，例如“继续”“按你刚才说的做”。
- `RESUME_TASK`：存在已保存的 `TaskScratchpad` 或上一轮 PAUSED 状态。
- `TOOL_DEPENDENT`：用户指向刚才工具/子 Agent 的结果，例如“把刚才查到的发出去”。

第一版可以用规则 + 小模型接口预留：

- 规则优先识别 `RESUME_TASK`、`TOOL_DEPENDENT`、明显续接词。
- 小模型负责判断 `NEW_TOPIC` / `RELATED` / `CONTINUATION`。
- 小模型失败时降级为保守模式：近期原文 + 摘要，但限制预算。

## Context Layers

上下文按层组织，但压缩时不是机械按层级删，而是根据当前 `stage` 和 `relevance` 计算必要性。

### L0 Required

永不压缩，最多做格式整理：

- 当前用户请求
- 当前阶段协议，比如 PLAN JSON schema、REFLECT JSON schema
- 必要安全规则和硬性路由规则

### L1 Capabilities

Agent / Tool 能力说明。

策略：

- 永远保留简短 Agent 能力表。
- 只在相关性命中时展开某类 Agent 的详细能力。
- 语音 voice list、浏览器工具列表等高 token 内容应按需展开。

### L2 Recent Conversation

最近若干轮原文。

策略：

- `NEW_TOPIC`：少量或不保留。
- `RELATED`：保留近期窗口。
- `CONTINUATION` / `TOOL_DEPENDENT`：强保留最近原文。
- 滑动压缩：当原文窗口超过阈值，压缩较早一半，保留较后一半。

### L3 Historical Summary

更早对话摘要。

策略：

- 由滑动压缩产生。
- 摘要应保留用户目标、约束、已作决定、开放问题。
- 远古且低相关摘要可以丢弃。

### L4 Task State

长任务清单、PAUSED 状态、已完成 / 未完成任务。

策略：

- `RESUME_TASK` 时优先级极高。
- 普通 `PLAN` 中只保留活跃任务摘要。
- 不活跃任务只保留 compact checklist。

### L5 Execution Results

工具结果和子 Agent 结果。

策略：

- 当前 scratchpad 中的结果在 `REFLECT` 阶段优先级很高。
- 历史工具结果优先级较低，应先摘要再丢弃。
- 大型工具输出必须保留 source、status、关键字段和错误信息，不保留完整日志。

## Token Budget

默认策略：

- 输入上下文最多使用模型窗口的 80%。
- 预留 20% 给模型输出、推理余量和工具调用 JSON。

实现上不要写死为“思考 token”，而是配置：

```java
ContextBudget {
    int maxContextTokens;
    double reservedOutputRatio;   // default 0.2
}
```

如果超过预算，压缩顺序为：

1. 裁剪不相关 Agent 的详细能力。
2. 摘要历史工具结果。
3. 摘要更早对话原文。
4. 丢弃远古低相关摘要。
5. 缩短当前无关任务清单。
6. 最后才缩短近期原文，但必须保留最近若干轮。

永不压缩：

- 当前用户请求。
- 当前 stage 的输出 schema。
- 当前 resume / reflect 所需的活跃 scratchpad 关键状态。

## Tool-loop runtime context

Spring AI 在单个 Agent 内部会持续追加 `AssistantMessage(tool_calls)` 和
`ToolResponseMessage`。这些消息必须和普通对话、DAG 节点上下文使用同一份预算，不能等到模型硬上限才停止。

- 每次模型调用前都由 `ToolLoopContextManager.prepare` 重新计算完整消息的 token，包括
  `ToolResponse.responseData` 和 tool-call arguments；不能使用 `Prompt.getContents()`，因为它不会覆盖完整工具结果。
- 输入工作集目标仍为模型窗口的 80%，128K 模型对应约 102.4K 输入预算，余下 20% 用于输出、推理和下一次工具调用。
- 完整工具结果在压缩前按 Agent 执行会话隔离写入
  `data/context/tool-transcripts/<session-id>.jsonl`。子 Agent 不能读取其他会话的隐式上下文，只能持有编排显式传入的引用。
- 工具循环第一次调用模型前，同时保存 `context-initial` 快照。当前指令、系统约束和初始任务状态仍属于工作上下文的必需层，归档快照只作为恢复兜底，不替代必需层。
- 预算允许时最多保留最近 4 个工具响应原文。超过预算后先把更早结果压成首尾摘要和
  `tool-transcript://<session>/<tool-call-id>` 引用；仍超预算时继续动态压缩最近结果，而不是强行保留固定数量。
- 压缩取舍顺序固定为：旧工具结果 -> 最近工具结果 -> 历史 tool-call 大参数 -> 成对移除最早交换。当前指令、系统规则和最新任务状态不参与这条删除链。
- 如果压缩结果不够，成对移除最早的 assistant tool-call 和 tool-response，避免产生无配对的协议消息；同时保留 session 级恢复提示。
- 模型缺少第一步或早期证据时，先调用 `list_archived_tool_results(sessionId, query)` 搜索轻量索引，再调用
  `read_archived_tool_result(reference, offsetToken, maxTokens)` 分页读取。单页最多 5000 token，禁止把整份历史重新装回 Prompt。
- Agent 完成时把 `tool_transcript_session` 和 `tool_initial_context_reference` 写入
  `AgentResult.signals`，由 DAG store 持久化并显式注入依赖节点；PAUSED 时写入 resume state。跨节点后期需要第一步证据时，因此仍能发现对应 session。
- transcript 默认按最后写入时间保留 7 天。应用启动时检查一次，之后写入时至多每小时检查一次并删除过期 JSONL；执行中的长任务持续写入，因此不会在运行中被清理。
- 128K 是模型声明的真实上下文上限，不应通过配置虚增。更大窗口模型可以同步提高
  `context-window-tokens`，但逐轮压缩和持久化仍必须保留。

普通长对话默认只保留最近 12 条原始消息（约 6 轮），更早消息进入滑动摘要。50 条原文在工具密集任务中会明显挤占执行结果预算，因此不再作为默认值。

## Integration

### MessageRouter

`MessageRouter` 仍然负责：

- 用户锁。
- 图片记忆处理。
- PAUSED scratchpad 读取与保存。
- Agent 执行循环。
- 最终 `ModelReply` 拼装。

但它不再直接决定“给 Orchestrator 哪些 history”。

### OrchestratorAgent

当前 `OrchestratorAgent.plan(UserRequest request)` 可以逐步演进。

第一阶段保留接口不变：

- `MessageRouter` 调用 `ContextEngineeringService`。
- 将 `ContextPackage.messages` 放入现有 `UserRequest.history` 字段。
- `OrchestratorAgentImpl` 使用已装配好的 messages，减少内部拼装逻辑。

后续阶段可改成：

```java
OrchestrationResult plan(ContextPackage context);
OrchestrationResult reflect(ContextPackage context);
```

### ConversationMemory

`ConversationMemory` 继续负责存储和读取原始历史、短期窗口、长期记忆。

新增上下文工程后，它不再承担完整上下文编排职责，只作为 Provider 的数据源。

## Error Handling

- 相关性小模型失败：降级到保守 `RELATED`。
- token 估算失败：按字符数近似估算。
- 压缩模型失败：使用规则摘要或截断。
- memory 读取失败：跳过历史层，但保留当前请求和系统能力。
- scratchpad 解析失败：记录日志，走普通 PLAN；不要阻断用户请求。

## Testing

新增单元测试覆盖：

- `NEW_TOPIC` 只保留基础上下文。
- `CONTINUATION` 保留近期原文。
- `RESUME_TASK` 强制包含 pause/resume 状态。
- `REFLECT` 阶段优先包含当前 scratchpad 结果。
- 超预算时按顺序压缩低优先级层。
- 当前用户请求永不被压缩或丢弃。
- 子 Agent instruction 不包含完整历史。

## Rollout Plan

第一步：新增数据结构和 `ContextEngineeringService` 默认实现，暂不改变行为。

第二步：把 `MessageRouter` 中 history 获取和 scratchpad resume prompt 拼接迁移到 Service。

第三步：让 `OrchestratorAgentImpl` 使用 `ContextPackage` 中的 messages。

第四步：加入相关性判断和预算压缩。

第五步：补充测试并清理旧的上下文拼装路径。

## Implementation Decisions

- 相关性判断第一版使用规则实现，并预留 `ContextRelevanceClassifier` 接口；暂不直接新增小模型调用，避免引入额外网络失败路径。
- token 估算第一版使用字符近似：中文按 1 字约 1 token，英文按 4 字符约 1 token，取保守较大值；后续可替换 tokenizer。
- 历史摘要新增 `ConversationWindowCompressor` 接口；默认实现可复用现有 `MemoryCompressionService` 的摘要能力，没有模型时使用规则压缩。
- 第一阶段不扩展 `UserRequest`，`ContextPackage.messages` 写入现有 `history` 字段，减少对 `OrchestratorAgent` 的接口冲击。
