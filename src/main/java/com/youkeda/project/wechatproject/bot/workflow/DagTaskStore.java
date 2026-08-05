package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;

import java.util.List;
import java.util.Optional;

public interface DagTaskStore {

    void save(DagTask task);

    List<DagTask> findActive();

    Optional<DagTask> findFocused();

    Optional<DagTask> findById(String dagId);

    void setFocused(String dagId);

    void recordAttempt(String dagId, DagNode node, int attemptNo, long startedAt,
                       long finishedAt, AgentResult result);
}
