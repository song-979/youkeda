package com.youkeda.project.wechatproject.agent.intent;

/**
 * 意图识别器接口。
 * <p>
 * 默认实现 {@link RegexIntentRecognizer} 使用正则匹配，
 * 后续可替换为 {@code LlmIntentRecognizer}（调用小模型进行语义意图识别）。
 * <p>
 * 识别时只看文本，图片/语音/视频等媒体内容由下游专业模型消费。
 */
public interface IntentRecognizer {

    /**
     * 根据用户文本判断意图。
     *
     * @param text 用户输入文本（不含图片等媒体内容）
     * @return 意图识别结果，不会返回 null
     */
    IntentResult recognize(String text);
}
