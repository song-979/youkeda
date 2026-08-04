# Youkeda 微信 Agent — Harness 工程评价与加固记录

> 评价日期：2026-07-31
> 修复 PR：[song-979/youkeda#31](https://github.com/song-979/youkeda/pull/31)（分支 `fix/harness-improvements`，9 个 commit）
> 评价范围：记忆系统、上下文管理、工具系统、编排系统、Agent 协作、状态管理

---

## 总体判断

这是一个**完成度相当高的单用户取向 Agent harness**：三层记忆（Markdown 真相源 + SQLite 可重建索引 + 内存热窗口）、plan/reflect 编排循环、PAUSED 跨轮恢复、MCP 工具进程管理都已落地，且刚完成过一次从 1950 行单文件到多 Agent 的成功拆分。主要短板在于：同步单线程消息管道、无界增长的若干状态、上下文 token 经济学粗糙、以及状态机的若干竞态。整体处于"个人项目精品 → 多人可用产品"之间的位置。

一句话总结：这套 harness 的"骨架"（分层记忆、编排循环、降级链、恢复协议）是高水平设计，问题集中在"毛细血管"——异步边界、竞态、无界增长、token 经济学。

---

## 一、记忆系统 ★★★★☆

### 做得好的

- "Markdown 为真相源，SQLite 为可重建索引"的分层哲学正确，索引可删可重建（`VectorMemoryIndex.java:41`）
- Markdown 感知分块带标题前缀（`VectorMemoryIndex.java:275-277`）、chunk 级 hash 增量索引避免重复 embed（`:521-525`）——成本控制意识出色
- 完整的降级链：向量检索 → 词法兜底 → 全量 bootstrap（`:94-104`）；LLM 摘要 → 关键词规则
- DREAMS.md "做梦但不自动信任"的保守巩固机制，设计意图很成熟

### 核心问题

1. 每轮对话触发一次 LLM 摘要调用，成本翻倍且无节流（`OpenClawConversationMemory.java:153-161`）；session 窗口纯 FIFO 丢弃（`:452-454`），被丢内容只能靠运气进入日记
2. 异步持久化复用单线程 cacheCleaner（`:89-99`）：LLM 摘要（秒级）与缓存清理挤在同一个调度器，无背压、无界队列
3. 暴力向量扫描 + JSON 文本存 embedding，无 ANN；`nothingChanged` 每次全量 SHA-256（`:240`）
4. 三类无界增长：审计表 `memory_index_queries`（含用户 query 明文，有隐私问题）、`userFileLocks`、`.scratchpad/*.json`
5. 正确性小问题：`extractJsonBlock` 大括号配平不识别字符串内花括号（`:608-620`）→ 静默丢记忆；`appendUserMessage` 可造成连续两条 user 消息（`:165-176`）；DREAMS.md 是"不被信任的候选"却照样注入 bootstrap（`:361-372`）；关键词降级里"我的"等宽泛匹配（`:1161`）积累噪音

---

## 二、上下文管理 ★★★☆☆

### 做得好的

- bootstrap 有字符预算（8000）+ 两级缓存（10s TTL + mtime 比对），避免了每轮全量重读
- `TaskScratchpad.toResumePrompt()` 的"已完成任务不要重复规划"指令是教科书级的恢复 prompt 设计

### 核心问题

1. 没有真正的 token 计数，全靠字符近似 + 硬截断，8000 字符处可能把 Markdown 切成半句（`:1287-1290`）
2. 工具结果是上下文的最大泄漏点：`browser_snapshot` 返回整棵 a11y 树无上限（`BrowserTools.java:293-300`）；`LocalFileTools` 把 base64 图片直接拼进工具返回文本（`LocalFileTools.java:229-245`），一张图就是几万 token
3. `toReflectPrompt()` 的 rawOutput 不截断（`TaskScratchpad.java:98`），长输出会撑爆 reflect prompt
4. PLAN prompt 约 120 行 + capabilities prompt 每轮全量发送，随 Agent 增多持续膨胀
5. 工具 schema 挤占上下文：小红书 14 个工具因分组遗漏全部注册进 ChatAgent 主运行时（`ToolService.java:87-96` 的排除清单不含 `xiaohongshu`）

---

## 三、工具系统 ★★★☆☆

### 做得好的

- `ProjectTool` 标记接口 + Spring 集合注入的发现机制简洁；`@ConditionalOnProperty` 按开关装配是正确姿势
- Browser 安全体系相对完整：`BrowserSecurityPolicy` 域名策略、审计日志、MCP 进程健康检查 + 连续失败冷却 + 最多 3 次重启
- 小红书 MCP 的 `SmartLifecycle` 管理是良好范本

### 核心问题

1. 工具循环没有步数上限和成本护栏，仅靠 prompt 里"尽量 20 步以内"靠模型自觉
2. 错误处理两种范式不一致：LocalFileTools 返回错误字符串（LLM 可自愈）vs BrowserTools 抛异常炸掉整个循环（`BrowserTools.java:150-155`）
3. 超时策略参差：ChatAgent 180s / BrowserAgent 600s / **TravelAgent 无超时**（`TravelAgent.java:89-92` 同步阻塞）
4. 分组逻辑三处重复且用 category 字符串 + instanceof 混合判断（`ToolService.java:93,111`）
5. Chrome 进程只生不灭：bean 创建即启动（`ToolService.java:286-290`）但无 destroy 钩子，JVM 退出留孤儿进程
6. **安全问题（高危）**：`SetupController` 无鉴权返回含 api-key 的完整配置（`SetupController.java:98-114`）且允许任意写；`buildFinalReply` 按 LLM 输出的 `[LOCAL_FILE:path]` 类标记读本机文件（`MessageRouter.java:640-648`），路径由模型输出决定

---

## 四、编排系统 ★★★★☆

### 做得好的

- plan/reflect 双 LLM 循环结构清晰，schema 三态（execute/completed/needs_clarification）设计合理，`temperature=0.0` 保证规划确定性
- **fallback 链是全项目最成熟的部分**：plan 失败→意图关键词兜底；reflect 失败→取最后 CHAT 文本强行完成；单任务失败→记录后继续。没有单点炸毁路径
- `specialCasePlan` 关键词捷径省 LLM 调用；PAUSED/Resume 协议（打车确认场景）是真实生产需求的沉淀
- reflect 里"禁止把疑似错误文本喂给 BROWSER"（`OrchestratorAgentImpl.java:180`）这类规则说明作者踩过坑

### 核心问题

1. LLM 调用经济学：单轮成功 = 1 plan + 1 reflect + Agent 内部循环；`IntentRouter`（设计文档 Phase 4）未实现，L1/L2 规则路由能省掉 80% 消息的 plan 调用
2. `parseTasks` 不校验 agent_type 是否已注册（`:433-467`），LLM 幻觉出的 agent 名到执行期才暴露
3. JSON 提取是"第一个 `{` 到最后一个 `}`"的贪婪截取（`:514-535`），reasoning 文本含花括号就会切错
4. 脆弱字符串匹配：`isLikelyErrorText`、`isContextTokenError` 靠文本/异常 message 猜测错误类型
5. reflect 每轮全量重述 scratchpad，未做增量评估

---

## 五、Agent 协作 ★★★☆☆

### 做得好的

- `AgentRegistry.generateCapabilitiesPrompt()` 自动从 Agent 声明生成路由清单，新增 Agent 编排层零改动
- `{{LAST_CHAT_TEXT}}` 产物引用约定 + `TaskScratchpad` 的产物查询方法解决了跨 Agent 产物传递
- `AgentBus` 的串行/并行委托 API 已就位

### 核心问题

1. `AgentBus` 尚未被真正使用，协作全部经由 Orchestrator 中转——要明确选择：落地直连或移除死代码
2. `delegateParallel` 用虚拟线程但 ThreadLocal 用户上下文不传播（`AgentBus.java:56-57`）→ 并行子任务里 RAG/滴滴工具拿不到用户身份
3. 执行模型全串行，独立任务（如"查天气+生成图"）无法并行
4. 能力声明（`getCapability()`）与 PLAN prompt 硬编码路由规则双份维护，两套真相

---

## 六、状态管理 ★★☆☆☆（评价时最需要加强的维度）

### 做得好的

- per-user `ReentrantLock` + ThreadLocal + per-user 文件锁的三层隔离在正常链路可靠
- 重启恢复覆盖面广：提醒重挂、scratchpad 恢复、iLink 上下文续期、向量索引持久化
- context token 过期时挂起提醒等用户下次发消息补发，是务实的工程决策

### 核心问题（按严重度）

1. 同步单线程消息管道（`BotService.java:82-96` → `MessageHandler.java:89-93`）：分钟级 browser 任务阻塞所有用户；"正在思考"提示在 `route()` 返回后才发（`:135-136`），顺序颠倒
2. Automation 全局单收件人（`AutomationRuntime.java:882-891`）：Reminder 无 owner 字段，B 用户建的提醒发给 A——多用户隐私泄漏
3. TRIGGERING 僵尸：崩溃在 TRIGGERING 的提醒永远无法清理或重发（`:846-848`、`:373-375`）
4. 触发竞态：读状态→改 TRIGGERING 跨两次 store 调用非原子（`:630-644`），与过期补发并发 → 重复发送
5. `JsonAutomationStore` 非原子写盘 + 坏文件起不来（`:142-149`）
6. 调度池仅 2 线程跑完整 LLM 编排 + `Thread.sleep(2000)` 重试（`:678,741-780`），两个长任务堵死所有提醒
7. 杂项：`routeScheduledTask` 不设置 ThreadLocal；resume-context.json 明文存 botToken；锁表/futures 只增不删；8 位 ID 前缀碰撞即覆盖

---

## 修复实施（PR #31）

按 ROI 排序的修复路线，每个 commit 聚焦一个维度，可逐个 review。

### Commit 地图

| Commit | 维度 | 内容 |
|---|---|---|
| `ad01631` | 安全 (P0) | `SetupController`：仅本机访问、敏感配置递归脱敏（api-key/token/secret/password）、写入 top-level 白名单、掩码值不回写覆盖真实密钥 |
| `d7b38e1` | 基础设施 | 新增字符串感知的 `JsonExtractUtil`：剥离 code fence 后定位首个完整 JSON 对象，识别字符串字面量与转义，替代两处朴素截取实现 |
| `56a8564` | 消息管道 (P0) | 消息处理切换到虚拟线程（慢任务不再阻塞 SDK 轮询线程与其他用户），executor 随上下文优雅关闭；"正在思考"提示前置到路由前发送 |
| `745c865` | Automation (P1) | `Reminder/RecurringTask` 增加 `ownerId`（兼容旧 JSON），按创建者路由、周期实例继承属主、过期补发只触达属主（修复串话与隐私泄漏）；`JsonAutomationStore` 内原子 PENDING→TRIGGERING 认领（消除重复发送竞态）；启动恢复将僵尸 TRIGGERING 回退 PENDING；tmp+原子 rename 写盘、损坏文件隔离重建（不再起不来）；调度 future 触发后自清理；`createTaskReminder` 60s 去重窗口；ID 加长到 12 位 |
| `123e427` | Agent 协作 (P2) | `AgentResult.ErrorKind` 结构化错误分类（rate-limit/timeout/auth/upstream/tool/validation），替代文本猜测；`AgentTask` 携带 `userId`；`AgentBus.delegate` 委托期间安装/清理 RAG/滴滴 ThreadLocal（修复虚拟线程并行委托的用户身份丢失） |
| `da695ff` | 记忆系统 (P1) | LLM 摘要节流为每 3 轮一次（首轮必做），成本降约 2/3，其余轮次走关键词规则；持久化迁移到独立有界线程池（原为共享单线程无界队列）；session 窗口合并连续同角色消息保持 user/assistant 交替；摘要 JSON 抽取切换到 `JsonExtractUtil`；DREAMS.md 注入标注"未审核候选"；审计表启动时清理 30 天前记录 + 查询文本只存 200 字符预览；`clear()` 联动清理锁与计数器 |
| `c63a62e` | 编排系统 (P1) | 新增 `IntentRouter`：L1 寒暄 / L2 明确单领域（天气/导航/生图）直连单任务计划，0 次编排 LLM 调用；复合、模糊、带图、超长消息保守回退完整 plan；`OrchestrationResult.skipReflection` 让高置信计划跳过 reflect（简单消息省 2/3 模型调用）；`parseTasks` 对照 `AgentRegistry` 拒绝幻觉 agent_type；编排 JSON 抽取切换到 `JsonExtractUtil`；`toReflectPrompt` 截断 instruction(500)/rawOutput(3000)；`fromJson` 单条坏记录跳过不再整体丢弃 |
| `22d75ef` | 工具系统 (P2) | 分组改为声明式配置（`agent.tools.groups.*` 可覆盖）+ 显式包含语义（未列出的 category 不注册到任何运行时），修复小红书 14 个工具 schema 泄漏进主运行时；`BrowserMcpProcess` 注册 `destroyMethod="stop"`（Chrome 不再成为孤儿进程）；`BrowserTools.safeCall` 错误即结果（LLM 可自愈）+ 工具结果 20k 字符硬上限；`LocalFileTools` 不再内联 base64 图片（文本模型无法利用，纯烧 token），死代码清理；`TravelAgent` 工具循环补 120s 超时 |
| `9581e62` | 路由装配 (P0/P1) | `IntentRouter` 注册并接入路由链（specialCasePlan 与完整 plan 之间）；公平 per-user 锁保证异步下回复顺序；`route()` 与 `routeScheduledTask()` 均安装/清理 Automation、RAG、滴滴 ThreadLocal（修复定时任务无用户身份）；任务分发时注入 `userId`；`[MOTOU_GIF:path]` 路径读取前校验可写根目录白名单（app 目录/user home/tmp），堵住 LLM 输出驱动的任意文件读取 |

### 评价问题 → 修复状态对照

| 评价问题 | 状态 |
|---|---|
| SetupController 密钥泄漏 / 任意写 | ✅ `ad01631` |
| LLM 输出标记路径任意文件读取 | ✅ `9581e62`（MOTOU_GIF 白名单） |
| 同步管道阻塞 + 进度提示顺序颠倒 | ✅ `56a8564` |
| Automation 串话 / 僵尸 / 竞态 / 非原子写盘 | ✅ `745c865` |
| 每轮一次 LLM 摘要、异步池无背压 | ✅ `da695ff` |
| IntentRouter 自动路由 | ✅ `c63a62e` |
| parseTasks 不校验 agent_type | ✅ `c63a62e` |
| JSON 抽取两处朴素实现 | ✅ `d7b38e1` + `da695ff` + `c63a62e` |
| reflect prompt 不截断 | ✅ `c63a62e` |
| browser_snapshot 无上限 / 错误范式不一致 | ✅ `22d75ef` |
| base64 图片内联工具文本 | ✅ `22d75ef` |
| 小红书工具泄漏进主运行时 / 分组硬编码 | ✅ `22d75ef` |
| Chrome 进程孤儿 | ✅ `22d75ef` |
| TravelAgent 无超时 | ✅ `22d75ef` |
| delegateParallel 用户上下文丢失 | ✅ `123e427` |
| 错误文本猜测（errorKind 结构化） | ✅ `123e427` |
| DREAMS.md 未信任内容直接注入 | ✅ `da695ff`（标注未审核） |
| 连续同角色消息破坏交替 | ✅ `da695ff` |
| 审计表 / 锁表无界增长 | ✅ `da695ff`（部分：`.scratchpad` 清理联动留待后续） |
| routeScheduledTask 缺 ThreadLocal / 8 位 ID / futures 泄漏 | ✅ `9581e62` / `745c865` |

### 验证

- `mvn compile` / `mvn test-compile` 通过
- 测试套件与 `main` 基线逐一比对：失败清单完全一致（36 个均为测试环境缺 API key 的预先存在问题），**零新增回归**

### 明确未做（建议后续 PR）

| 事项 | 原因 |
|---|---|
| ANN 向量索引（sqlite-vec） | 当前暴力扫描在小数据量下可接受，属规模化前技术债 |
| plan DAG 并行执行 | 需 plan schema 标注依赖关系，改动面大 |
| Orchestrator HTTP 统一到 `AiService` | `AiModelClient` 接口不支持 per-call temperature（编排要求 0.0），需先扩展接口 |
| 工具循环 maxSteps / token / 耗时三重熔断 | 需要 Spring AI tool loop 的拦截点设计 |
| jtokkit token 计数替代字符近似 | 引入新依赖，需先确定预算分配策略 |
| 调度池 2 线程跑 LLM 编排的拆分 | 调度模型重构，与 DAG 并行一起做更划算 |
| 能力声明与路由规则单一真相源 | 需要 PLAN prompt 生成式重构 |
