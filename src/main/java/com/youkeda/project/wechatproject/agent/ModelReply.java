package com.youkeda.project.wechatproject.agent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 模型回复统一格式。
 * <p>
 * 支持回复类型：
 * <ul>
 *   <li>{@code TEXT} —— 纯文本回复（普通对话）</li>
 *   <li>{@code IMAGE} —— 纯图片回复（文生图）</li>
 *   <li>{@code MIXED} —— 文本+图片混合回复</li>
 *   <li>{@code VOICE} —— 语音回复</li>
 *   <li>{@code FILE} —— 文件回复</li>
 * </ul>
 * <p>
 * AgentSink 根据 {@link #type} 决定如何通过 iLink 发送给用户。
 */
public class ModelReply {

    public enum Type {
        TEXT,
        IMAGE,
        MIXED,
        VOICE,
        FILE
    }

    /** 图片二进制 + 文件名 */
    public record ImagePayload(byte[] bytes, String fileName) {
        public ImagePayload {
            Objects.requireNonNull(bytes, "image bytes must not be null");
            Objects.requireNonNull(fileName, "image fileName must not be null");
        }
    }

    /** 语音二进制 + 格式信息 */
    public record AudioPayload(byte[] bytes, String format, int durationMs, int sampleRate) {
        public AudioPayload {
            Objects.requireNonNull(bytes, "audio bytes must not be null");
            Objects.requireNonNull(format, "audio format must not be null");
        }
    }

    /** 文件二进制 + 文件名 */
    public record FilePayload(byte[] bytes, String fileName) {
        public FilePayload {
            Objects.requireNonNull(bytes, "file bytes must not be null");
            Objects.requireNonNull(fileName, "file fileName must not be null");
        }
    }

    private final Type type;
    private final String textContent;
    private final List<ImagePayload> images;
    private final AudioPayload audioPayload;
    private final FilePayload filePayload;

    ModelReply(Type type, String textContent, List<ImagePayload> images, AudioPayload audioPayload, FilePayload filePayload) {
        this.type = type;
        this.textContent = textContent;
        this.images = images != null ? Collections.unmodifiableList(images) : Collections.emptyList();
        this.audioPayload = audioPayload;
        this.filePayload = filePayload;
    }

    public Type getType() { return type; }
    public String getTextContent() { return textContent; }
    public List<ImagePayload> getImages() { return images; }
    public AudioPayload getAudioPayload() { return audioPayload; }
    public FilePayload getFilePayload() { return filePayload; }

    // ---- 工厂方法 ----

    public static ModelReply text(String text) {
        return new ModelReply(Type.TEXT, text, Collections.emptyList(), null, null);
    }

    public static ModelReply image(byte[] bytes, String fileName) {
        ImagePayload payload = new ImagePayload(bytes, fileName);
        // 生图场景也附带一个友好提示文本
        return new ModelReply(Type.IMAGE, null, List.of(payload), null, null);
    }

    public static ModelReply mixed(String text, List<ImagePayload> images) {
        return new ModelReply(Type.MIXED, text, images, null, null);
    }

    public static ModelReply voice(byte[] audioBytes, String format, int durationMs, int sampleRate) {
        AudioPayload payload = new AudioPayload(audioBytes, format, durationMs, sampleRate);
        return new ModelReply(Type.VOICE, null, Collections.emptyList(), payload, null);
    }

    public static ModelReply file(byte[] fileBytes, String fileName) {
        FilePayload payload = new FilePayload(fileBytes, fileName);
        return new ModelReply(Type.FILE, null, Collections.emptyList(), null, payload);
    }

    public static ModelReply mixedWithFile(String text, List<ImagePayload> images, FilePayload filePayload) {
        return new ModelReply(Type.MIXED, text, images, null, filePayload);
    }
}
