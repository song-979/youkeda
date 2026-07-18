package com.youkeda.project.wechatproject.agent.routing;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 模型回复统一格式。
 * <p>
 * 支持三种回复类型：
 * <ul>
 *   <li>{@code TEXT} —— 纯文本回复（普通对话）</li>
 *   <li>{@code IMAGE} —— 纯图片回复（文生图）</li>
 *   <li>{@code MIXED} —— 文本+图片混合回复</li>
 * </ul>
 * <p>
 * AgentSink 根据 {@link #type} 决定如何通过 iLink 发送给用户。
 */
public class ModelReply {

    public enum Type {
        TEXT,
        IMAGE,
        MIXED
    }

    /** 图片二进制 + 文件名 */
    public record ImagePayload(byte[] bytes, String fileName) {
        public ImagePayload {
            Objects.requireNonNull(bytes, "image bytes must not be null");
            Objects.requireNonNull(fileName, "image fileName must not be null");
        }
    }

    private final Type type;
    private final String textContent;
    private final List<ImagePayload> images;

    private ModelReply(Type type, String textContent, List<ImagePayload> images) {
        this.type = type;
        this.textContent = textContent;
        this.images = images != null ? Collections.unmodifiableList(images) : Collections.emptyList();
    }

    public Type getType() { return type; }
    public String getTextContent() { return textContent; }
    public List<ImagePayload> getImages() { return images; }

    // ---- 工厂方法 ----

    public static ModelReply text(String text) {
        return new ModelReply(Type.TEXT, text, Collections.emptyList());
    }

    public static ModelReply image(byte[] bytes, String fileName) {
        ImagePayload payload = new ImagePayload(bytes, fileName);
        // 生图场景也附带一个友好提示文本
        return new ModelReply(Type.IMAGE, null, List.of(payload));
    }

    public static ModelReply mixed(String text, List<ImagePayload> images) {
        return new ModelReply(Type.MIXED, text, images);
    }
}
