package com.youkeda.project.wechatproject.agent;

import java.io.IOException;
import java.util.List;

/**
 * AI 模型调用抽象。
 * 实现类负责具体的 HTTP 协议通信，调用方仅依赖此接口。
 * <p>
 * 切换模型只需提供新的实现并注册为 Spring Bean。
 */
public interface AiModelClient {

    /**
     * 发送用户消息，附带历史上下文和可选图片。
     *
     * @param userMessage     用户输入的文本
     * @param imageBase64Urls 图片 data URI 列表（如 "data:image/png;base64,..."），传空集合/null 表示无图片
     * @param history         历史对话消息（按时间顺序），传空集合/null 表示无历史
     * @return AI 回复文本，不应返回 null
     * @throws IOException 网络或 API 调用失败时抛出
     */
    String chat(String userMessage, List<String> imageBase64Urls, List<ChatRequest.Message> history) throws IOException;
}
