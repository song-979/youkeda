package com.youkeda.project.wechatproject.bot.context;

public interface ContextRelevanceClassifier {
    ContextRelevance classify(ContextBuildRequest request);
}
