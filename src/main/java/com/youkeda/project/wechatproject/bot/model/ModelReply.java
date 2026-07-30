package com.youkeda.project.wechatproject.bot.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ModelReply {

    public enum Type {
        TEXT,
        IMAGE,
        MIXED,
        VOICE,
        FILE
    }

    public record ImagePayload(byte[] bytes, String fileName) {
        public ImagePayload {
            Objects.requireNonNull(bytes, "image bytes must not be null");
            Objects.requireNonNull(fileName, "image fileName must not be null");
        }
    }

    public record AudioPayload(byte[] bytes, String format, int durationMs, int sampleRate) {
        public AudioPayload {
            Objects.requireNonNull(bytes, "audio bytes must not be null");
            Objects.requireNonNull(format, "audio format must not be null");
        }
    }

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

    public ModelReply(Type type, String textContent, List<ImagePayload> images,
                      AudioPayload audioPayload, FilePayload filePayload) {
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

    public static ModelReply text(String text) {
        return new ModelReply(Type.TEXT, text, Collections.emptyList(), null, null);
    }

    public static ModelReply image(byte[] bytes, String fileName) {
        ImagePayload payload = new ImagePayload(bytes, fileName);
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
