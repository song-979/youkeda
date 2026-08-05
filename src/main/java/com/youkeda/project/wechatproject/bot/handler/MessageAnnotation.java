package com.youkeda.project.wechatproject.bot.handler;

/**
 * Unified annotation builder for user messages.
 * Produces a consistent format consumed by the orchestrator system prompt.
 *
 * <p>Output format:
 * <pre>
 * 【系统注解】&lt;annotation&gt;
 * 【用户消息】
 * &lt;raw text&gt;
 * </pre>
 */
public final class MessageAnnotation {

    private static final String SYS_PREFIX = "【系统注解】";
    private static final String USER_MSG_HEADER = "【用户消息】";
    private static final String IMAGE_ONLY_HINT = "用户发送了图片，但未附带文字说明。请根据图片内容询问用户需求。";

    private MessageAnnotation() {}

    /**
     * Builds an annotated user message for the orchestrator.
     *
     * @param rawText        original user typed text (may be null/blank)
     * @param fileAnnotation pre-built file content annotation, or null
     * @param voiceAnnotation pre-built voice transcript annotation, or null
     * @param hasImages      whether the user sent images without text
     * @return annotated message string
     */
    public static String build(String rawText, String fileAnnotation,
                                String voiceAnnotation, boolean hasImages) {
        StringBuilder sb = new StringBuilder();

        if (fileAnnotation != null && !fileAnnotation.isBlank()) {
            sb.append(SYS_PREFIX).append(fileAnnotation).append("\n\n");
        }
        if (voiceAnnotation != null && !voiceAnnotation.isBlank()) {
            sb.append(SYS_PREFIX).append(voiceAnnotation).append("\n\n");
        }

        boolean hasRawText = rawText != null && !rawText.isBlank();
        if (hasImages && !hasRawText) {
            sb.append(SYS_PREFIX).append(IMAGE_ONLY_HINT).append("\n\n");
        }

        if (hasRawText) {
            sb.append(USER_MSG_HEADER).append("\n").append(rawText);
        }

        return sb.toString();
    }

    /** File upload annotation: type description + extracted content. */
    public static String fileAnnotation(String fileName, String typeDesc,
                                         String content, int imageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户上传文件: ").append(fileName);
        if (typeDesc != null && !typeDesc.isBlank()) {
            sb.append("（").append(typeDesc).append("）");
        }
        if (content != null && !content.isBlank()) {
            sb.append("\n文档内容：\n").append(content);
        } else {
            sb.append("\n（文件无文字内容）");
        }
        if (imageCount > 0) {
            sb.append("\n（文档包含 ").append(imageCount).append(" 张嵌入图片，已提取并附带在消息中）");
        }
        return sb.toString();
    }

    /** Voice message annotation: speech-to-text transcript. */
    public static String voiceAnnotation(String transcript) {
        return "用户语音消息\n语音识别结果：\n" + transcript;
    }
}
