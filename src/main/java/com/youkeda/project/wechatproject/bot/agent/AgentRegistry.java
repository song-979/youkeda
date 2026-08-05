package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceCatalog;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceProfile;
import com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, AgentUnit> agents = new ConcurrentHashMap<>();
    private final VoiceCatalog voiceCatalog;

    public AgentRegistry(List<AgentUnit> agentUnits, VoiceCatalog voiceCatalog) {
        this.voiceCatalog = voiceCatalog;
        for (AgentUnit unit : agentUnits) {
            agents.put(unit.getName(), unit);
            log.info("registered agent unit: {} ({})", unit.getName(), unit.getCapability().description());
        }
    }

    public AgentUnit get(String name) {
        AgentUnit unit = agents.get(name);
        if (unit == null) {
            throw new IllegalArgumentException("Unknown agent type: " + name + ". Available: " + agents.keySet());
        }
        return unit;
    }

    public boolean contains(String name) {
        return agents.containsKey(name);
    }

    /** Simple capabilities summary (used by tests and fallback contexts). */
    public String generateCapabilitiesPrompt() {
        return generateCapabilitiesPrompt(null);
    }

    /**
     * Generate a complete routing guide for the orchestrator LLM.
     * Includes agent descriptions, trigger keywords, skill summaries, routing priority, and voice catalog.
     *
     * @param skillTools optional SkillTools for injecting per-agent skill summaries; may be null
     */
    public String generateCapabilitiesPrompt(SkillTools skillTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Agents\n\n");

        for (AgentUnit unit : agents.values().stream()
                .sorted(Comparator.comparing(AgentUnit::getName)).toList()) {
            AgentCapability cap = unit.getCapability();
            String agentName = unit.getName();

            sb.append("### ").append(agentName).append("\n");
            sb.append("**Description**: ").append(cap.description()).append("\n");
            sb.append("**Output type**: ").append(cap.outputType()).append("\n");

            if (!cap.routingKeywords().isEmpty()) {
                sb.append("**Trigger keywords**: ").append(String.join(", ", cap.routingKeywords())).append("\n");
            }
            sb.append("**Direct route**: ").append(cap.directRouteEligible()).append("\n");

            if (!cap.strengths().isEmpty()) {
                sb.append("**Capabilities**: ").append(String.join(", ", cap.strengths())).append("\n");
            }

            // Inject per-agent skill summary (condensed to keep orchestrator prompt manageable)
            if (skillTools != null) {
                String skillSummary = skillTools.getSkillsSummary(agentName);
                if (skillSummary != null && !skillSummary.isEmpty()) {
                    String condensed = skillSummary.length() > 500
                            ? skillSummary.substring(0, 500) + "\n... (more skills in agent's internal prompt)"
                            : skillSummary;
                    sb.append("**Registered skills**: ").append(condensed).append("\n");
                }
            }

            sb.append("\n");
        }

        // Routing priority — most specific agents first
        sb.append("## Routing Priority\n");
        sb.append("Prefer the MOST SPECIFIC agent for the user's request. "
                + "Check trigger keywords first, then fall back to CHAT.\n");
        List<AgentUnit> sorted = agents.values().stream()
                .sorted(Comparator.comparingInt(
                        (AgentUnit u) -> u.getCapability().routingKeywords().size()).reversed())
                .toList();
        for (AgentUnit unit : sorted) {
            AgentCapability cap = unit.getCapability();
            if (!cap.routingKeywords().isEmpty()) {
                sb.append("- **").append(unit.getName())
                  .append("**: ").append(cap.routingKeywords().size()).append(" trigger keywords\n");
            }
        }
        sb.append("- **CHAT**: default fallback (no keywords needed)\n\n");

        // Voice catalog (if SPEECH_GEN is present)
        if (agents.containsKey("SPEECH_GEN") && voiceCatalog != null) {
            sb.append(voiceCatalog.generateVoicePrompt());
        }

        return TokenBudgetUtil.truncateAtBoundary(sb.toString(), 3000);
    }

    public Map<String, AgentUnit> all() {
        return Map.copyOf(agents);
    }
}
