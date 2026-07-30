package com.youkeda.project.wechatproject.bot.agent;

import java.io.IOException;
import java.util.function.Consumer;

public interface AgentUnit {
    String getName();

    AgentCapability getCapability();

    AgentResult execute(AgentTask task) throws IOException;

    /**
     * Execute with optional progress callback.
     * Agents that support progress reporting (e.g. long-running browser ops)
     * should override this method. The default ignores progress.
     */
    default AgentResult execute(AgentTask task, Consumer<String> onProgress) throws IOException {
        return execute(task);
    }
}
