package com.youkeda.project.wechatproject.bot.agent;

import java.util.List;

public record AgentCapability(String name, String description, List<String> strengths, String outputType) {
    public AgentCapability {
        strengths = strengths != null ? List.copyOf(strengths) : List.of();
    }
}
