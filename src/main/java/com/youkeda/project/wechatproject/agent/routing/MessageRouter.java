package com.youkeda.project.wechatproject.agent.routing;

import com.youkeda.project.wechatproject.agent.AiModelClient;
import com.youkeda.project.wechatproject.agent.ChatRequest;
import com.youkeda.project.wechatproject.agent.ConversationMemory;
import com.youkeda.project.wechatproject.agent.ImageGenClient;
import com.youkeda.project.wechatproject.agent.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息路由调度器。
 * <p>
 * 职责：根据意图结果将请求路由到对应模型，统一处理记忆读写。
 * <p>
 * 所有对话路径（chat / image_gen）都读取和写入 {@link ConversationMemory}，
 * 确保后续对话有完整的上下文。
 */
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final AiModelClient chatClient;
    private final ImageGenClient imageGenClient;
    private final ConversationMemory memory;

    public MessageRouter(AiModelClient chatClient,
                         ImageGenClient imageGenClient,
                         ConversationMemory memory) {
        this.chatClient = chatClient;
        this.imageGenClient = imageGenClient;
        this.memory = memory;
    }

    /**
     * 路由执行。
     *
     * @param userId          微信用户 ID
     * @param text            用户输入文本
     * @param imageBase64Urls 图片 data URI 列表
     * @param intent          意图识别结果
     * @return 模型回复，不会返回 null
     * @throws IOException 网络或 API 调用失败时抛出
     */
    public ModelReply route(String userId, String text,
                            List<String> imageBase64Urls,
                            IntentResult intent) throws IOException {

        // 1. 读历史（所有路径都读）
        List<ChatRequest.Message> history = memory != null
                ? memory.getHistory(userId) : List.of();

        if (intent.isImageGen()) {
            return routeImageGen(userId, text, imageBase64Urls, history, intent);
        } else {
            return routeChat(userId, text, imageBase64Urls, history);
        }
    }

    // ---- chat 路径 ----

    private ModelReply routeChat(String userId, String text,
                                 List<String> imageBase64Urls,
                                 List<ChatRequest.Message> history) throws IOException {
        log.info("routing to chat: user={}, textLen={}, images={}, historyRounds={}",
                userId, text.length(), imageBase64Urls.size(), history.size() / 2);

        String reply = chatClient.chat(text, imageBase64Urls, history);
        if (memory != null) {
            memory.append(userId, text, reply);
        }
        return ModelReply.text(reply);
    }

    // ---- image_gen 路径 ----

    private ModelReply routeImageGen(String userId, String text,
                                     List<String> imageBase64Urls,
                                     List<ChatRequest.Message> history,
                                     IntentResult intent) throws IOException {

        String prompt = intent.getPrompt() != null ? intent.getPrompt() : text;

        // 有历史上下文时，用 chat 模型补全模糊的 prompt（如"再生成一张" → "再生成一张小猫的图片"）
        if (!history.isEmpty()) {
            prompt = contextualizePrompt(prompt, history);
        }

        if (imageGenClient == null) {
            log.info("routing to image_gen (disabled): user={}, prompt={}", userId, prompt);
            if (memory != null) {
                memory.appendUserMessage(userId, text);
            }
            throw new IOException("图片生成功能暂未开放，请稍后再试");
        }

        log.info("routing to image_gen: user={}, prompt={}", userId, prompt);
        byte[] imageBytes = imageGenClient.generate(prompt);
        if (memory != null) {
            memory.appendUserMessage(userId, text);
        }
        return ModelReply.image(imageBytes, "generated.png");
    }

    /**
     * 利用 chat 模型和对话历史，将用户的模糊请求补全为完整的图片生成 prompt。
     * <p>
     * 例如：历史中有"生成一只小猫"，用户说"再生成一张" → 补全为"再生成一张小猫的图片"。
     */
    private String contextualizePrompt(String prompt, List<ChatRequest.Message> history) throws IOException {
        List<ChatRequest.Message> contextMessages = new ArrayList<>(history);
        contextMessages.add(new ChatRequest.Message("user",
                "根据以上对话历史，把下面这条图片生成请求补充完整，使其可以独立理解。"
                        + "只输出补全后的一句话 prompt，不要加任何解释。\n\n"
                        + "请求：" + prompt));
        String result = chatClient.chat("", List.of(), contextMessages);
        if (result != null && !result.isBlank()) {
            log.info("contextualized prompt: \"{}\" → \"{}\"", prompt, result);
            return result.trim();
        }
        return prompt;
    }
}
