package com.youkeda.project.wechatproject.bot.memory;

import java.util.List;
import java.util.Optional;

/**
 * Per-agent persistent memory for long-term user profile and state.
 *
 * <p>Unlike {@link ConversationMemory} which stores ephemeral conversation turns
 * shared across all agents, AgentMemory stores durable facts scoped to a single
 * agent type (e.g. TRAVEL remembering home address, BROWSER remembering login state).
 *
 * <p>Not yet implemented — defined as an extension point for future phases.
 */
public interface AgentMemory {

    /** Store a key-value fact for a user. */
    void remember(String userId, String key, String value);

    /** Look up a fact by exact key. */
    Optional<String> recall(String userId, String key);

    /** Semantic search over stored facts. */
    List<String> search(String userId, String query, int topK);

    /** Delete a specific fact. */
    void forget(String userId, String key);

    /** Clear all facts for a user. */
    void clear(String userId);
}
