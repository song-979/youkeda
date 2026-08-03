package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

public interface ContextTokenEstimator {

    int estimate(String text);

    default int estimateMessages(List<ChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatRequest.Message message : messages) {
            total += estimate(message.getRole());
            total += estimate(String.valueOf(message.getContent()));
            total += 4;
        }
        return total;
    }
}
