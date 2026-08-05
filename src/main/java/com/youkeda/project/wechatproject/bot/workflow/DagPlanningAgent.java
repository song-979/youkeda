package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.model.UserRequest;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;

import java.util.List;

/** LLM boundary. Implementations propose semantics; backend code compiles and validates the DAG. */
public interface DagPlanningAgent {

    DagPlanDraft planDag(UserRequest request, List<String> validationErrors);

    DagReflection reflectDag(UserRequest request, String compactSnapshot, List<String> validationErrors);

    default DagReflection reflectDag(UserRequest request, ContextTaskState taskState,
                                     List<String> validationErrors) {
        return reflectDag(request, taskState != null ? taskState.toString() : "", validationErrors);
    }
}
