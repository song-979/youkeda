package com.youkeda.project.wechatproject.agent.orchestration;

import com.youkeda.project.wechatproject.agent.speech.VoiceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public String generateCapabilitiesPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available agent units:\n");
        for (AgentUnit unit : agents.values()) {
            AgentCapability cap = unit.getCapability();
            sb.append("- ").append(unit.getName()).append(": ").append(cap.description()).append("\n");
            sb.append("  strengths: ").append(String.join(", ", cap.strengths())).append("\n");
            sb.append("  output: ").append(cap.outputType()).append("\n");
        }
        if (agents.containsKey("SPEECH_GEN") && voiceCatalog != null) {
            sb.append(voiceCatalog.generateVoicePrompt());
        }
        return sb.toString();
    }

    public Map<String, AgentUnit> all() {
        return Map.copyOf(agents);
    }
}
