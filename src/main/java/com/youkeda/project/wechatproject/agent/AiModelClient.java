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
     * 发送用户消息，附带历史上下文和可选图片（非流式）。
     *
     * @param userMessage     用户输入的文本
     * @param imageBase64Urls 图片 data URI 列表（如 "data:image/png;base64,..."），传空集合/null 表示无图片
     * @param history         历史对话消息（按时间顺序），传空集合/null 表示无历史
     * @return AI 回复文本，不应返回 null
     * @throws IOException 网络或 API 调用失败时抛出
     */
    String chat(String userMessage, List<String> imageBase64Urls, List<ChatRequest.Message> history) throws IOException;

    /**
     * 流式对话（SSE），内部累积完整回复后返回。
     * <p>
     * 与非流式相比，此方法使用 SSE 协议逐 chunk 读取，避免长时间无数据导致 read timeout。
     * 调用方仍然同步等待完整结果，适合不能增量发送的场景（如微信消息）。
     *
     * @return AI 回复文本，不应返回 null
     * @throws IOException 网络或 API 调用失败时抛出
     */
    default String chatStream(String userMessage, List<String> imageBase64Urls,
                              List<ChatRequest.Message> history) throws IOException {
        return chat(userMessage, imageBase64Urls, history);
    }
}
