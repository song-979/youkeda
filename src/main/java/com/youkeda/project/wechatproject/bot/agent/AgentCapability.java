package com.youkeda.project.wechatproject.bot.agent;

import java.util.List;

/**
 * Describes an agent's capabilities for dynamic orchestrator prompt generation.
 *
 * @param name            capability name (e.g. "browser-automation")
 * @param description     human-readable description of what this agent does
 * @param strengths       capability tags for matching (e.g. "web-navigation")
 * @param outputType      output type: "text", "image", "voice"
 * @param routingKeywords trigger keywords for the orchestrator to route to this agent;
 *                        empty list means this agent has no keyword triggers
 *                        (e.g. CHAT is the default fallback)
 * @param directRouteEligible whether one unambiguous keyword match may bypass the orchestration LLM
 */
public record AgentCapability(String name, String description, List<String> strengths, String outputType,
                              List<String> routingKeywords, boolean directRouteEligible) {

    public AgentCapability {
        strengths = strengths != null ? List.copyOf(strengths) : List.of();
        routingKeywords = routingKeywords != null ? List.copyOf(routingKeywords) : List.of();
    }

    /** Backward-compatible constructor without routing keywords. */
    public AgentCapability(String name, String description, List<String> strengths, String outputType) {
        this(name, description, strengths, outputType, List.of(), false);
    }

    /** Backward-compatible constructor for capabilities that require full orchestration. */
    public AgentCapability(String name, String description, List<String> strengths, String outputType,
                           List<String> routingKeywords) {
        this(name, description, strengths, outputType, routingKeywords, false);
    }
}
