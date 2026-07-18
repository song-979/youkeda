package com.youkeda.project.wechatproject.agent.intent;

import java.util.regex.Pattern;

/**
 * 基于正则表达式的意图识别器（当前默认实现）。
 * <p>
 * 匹配文生图关键词——"生成一张"、"画一个"、"文生图"等。
 * 未匹配到则返回 CHAT。
 * <p>
 * 后续替换为 LLM 实现的 {@link IntentRecognizer} 后此类可废弃。
 */
public class RegexIntentRecognizer implements IntentRecognizer {

    /**
     * 文生图意图关键词匹配。
     * 匹配："生成一张"、"画一张"、"文生图"、"生成图片"、"帮我生成"、"给我画个" 等。
     */
    private static final Pattern IMAGE_GEN_PATTERN = Pattern.compile(
            "(生成|画|绘制|创建|做).{0,2}(一张|一个|个|张|幅|图片|图|照片)"
            + "|文生图"
            + "|(生成|画)个"
            + "|(帮我|给我|帮忙)(生成|画|绘制).*(图|图片|照片)"
            + "|^(生成|画|绘制).*(图|图片|照片)"
    );

    @Override
    public IntentResult recognize(String text) {
        if (text == null || text.isEmpty()) {
            return IntentResult.chat();
        }
        if (IMAGE_GEN_PATTERN.matcher(text).find()) {
            return IntentResult.imageGen(text);
        }
        return IntentResult.chat();
    }
}
