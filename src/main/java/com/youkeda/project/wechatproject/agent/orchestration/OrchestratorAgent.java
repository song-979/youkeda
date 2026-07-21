package com.youkeda.project.wechatproject.agent.orchestration;

/**
 * 编排器接口，是意图识别模型的升级版。
 * <p>
 * 替代原有的 {@code IntentRecognizer} 顶层角色，负责：
 * <ul>
 *   <li>深度理解用户需求</li>
 *   <li>判断是否需要追问</li>
 *   <li>分解任务并生成优化的子模型提示词</li>
 *   <li>执行后反思校验，决定是继续、重试还是结束</li>
 * </ul>
 */
public interface OrchestratorAgent {

    /**
     * 首次规划：分析用户请求，输出执行计划或追问。
     *
     * @param request 用户请求上下文（文本、图片、历史）
     * @return 编排结果（可能是追问、执行计划或失败）
     */
    OrchestrationResult plan(UserRequest request);

    /**
     * 执行后反思：拿到子模型执行结果后，评估是否满意，决定下一步。
     * <p>
     * scratchpad 中包含了本轮所有已执行 task 的指令和输出，
     * 编排模型基于这些信息判断：继续执行/重试/完成。
     *
     * @param scratchpad 任务草稿本（含所有执行历史）
     * @return 编排结果（可能是新的执行计划或最终回复）
     */
    OrchestrationResult reflect(TaskScratchpad scratchpad);
}
