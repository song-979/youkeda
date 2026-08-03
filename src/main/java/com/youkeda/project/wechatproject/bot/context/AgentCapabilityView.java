package com.youkeda.project.wechatproject.bot.context;

import java.util.List;

public record AgentCapabilityView(String name, String description, List<String> strengths, String outputType) {

    public AgentCapabilityView {
        strengths = strengths != null ? List.copyOf(strengths) : List.of();
    }
}
