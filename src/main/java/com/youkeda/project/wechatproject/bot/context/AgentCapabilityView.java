package com.youkeda.project.wechatproject.bot.context;

import java.util.List;

public record AgentCapabilityView(String name, String description, List<String> strengths, String outputType,
                                  List<String> routingKeywords, boolean directRouteEligible) {

    public AgentCapabilityView {
        strengths = strengths != null ? List.copyOf(strengths) : List.of();
        routingKeywords = routingKeywords != null ? List.copyOf(routingKeywords) : List.of();
    }

    public AgentCapabilityView(String name, String description, List<String> strengths, String outputType) {
        this(name, description, strengths, outputType, List.of(), false);
    }
}
