package com.youkeda.project.wechatproject.agent.orchestration;

import java.util.List;

/**
 * 子模型能力声明，供编排模型选择子模型时参考。
 */
public record AgentCapability(
        String name,
        String description,
        List<String> strengths,
        String outputType
) {
    public AgentCapability {
        strengths = strengths != null ? List.copyOf(strengths) : List.of();
    }
}
