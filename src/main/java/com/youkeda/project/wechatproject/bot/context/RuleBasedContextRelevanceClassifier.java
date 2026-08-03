package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.orchestrator.TaskScratchpad;

import java.util.Locale;

public class RuleBasedContextRelevanceClassifier implements ContextRelevanceClassifier {

    @Override
    public ContextRelevance classify(ContextBuildRequest request) {
        if (request == null) {
            return ContextRelevance.NEW_TOPIC;
        }

        TaskScratchpad scratchpad = request.scratchpad();
        if (request.stage() == ContextStage.RESUME
                || (scratchpad != null && !scratchpad.isEmpty())) {
            return ContextRelevance.RESUME_TASK;
        }

        String text = normalize(request.currentMessage());
        if (text.isBlank()) {
            return request.recentHistory().isEmpty()
                    ? ContextRelevance.NEW_TOPIC
                    : ContextRelevance.RELATED;
        }

        if (containsAny(text, "刚才查到", "刚查到", "查到的", "搜索到", "工具结果", "结果发",
                "发出去", "发布出去", "刚才生成", "刚生成", "刚才的内容", "上一轮结果")) {
            return ContextRelevance.TOOL_DEPENDENT;
        }

        if (containsAny(text, "继续", "接着", "按刚才", "按你说", "照刚才", "上一轮", "上面那个",
                "刚才那个", "这个方案", "这个设计", "前面说的", "继续做")) {
            return ContextRelevance.CONTINUATION;
        }

        if (!request.recentHistory().isEmpty() && containsAny(text, "这个", "那个", "它", "这件事", "刚刚")) {
            return ContextRelevance.RELATED;
        }

        return ContextRelevance.NEW_TOPIC;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
