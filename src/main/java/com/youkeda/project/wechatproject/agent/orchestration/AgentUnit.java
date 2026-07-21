package com.youkeda.project.wechatproject.agent.orchestration;

import java.io.IOException;

/**
 * 子模型统一接口。
 * <p>
 * 所有可被编排模型调用的子模型都实现此接口。
 * 通过 Spring 的自动发现机制注册到 {@link AgentRegistry}。
 */
public interface AgentUnit {

    /** 子模型唯一标识（如 CHAT、IMAGE_GEN、SPEECH_GEN） */
    String getName();

    /** 子模型能力描述（供编排模型选择时参考） */
    AgentCapability getCapability();

    /**
     * 执行编排模型下发的任务。
     *
     * @param task 编排模型生成的子任务
     * @return 执行结果
     * @throws IOException 网络或 API 调用失败
     */
    AgentResult execute(AgentTask task) throws IOException;
}
