package com.youkeda.project.wechatproject.bot.model;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

public class UserRequest {

    private final String userId;
    private final String text;
    private final List<String> imageBase64Urls;
    private final List<ChatRequest.Message> history;
    private final List<String> rememberedImageBase64Urls;
    private final String rememberedImageSummary;

    public UserRequest(String userId, String text, List<String> imageBase64Urls, List<ChatRequest.Message> history) {
        this(userId, text, imageBase64Urls, history, List.of(), null);
    }

    public UserRequest(String userId, String text, List<String> imageBase64Urls,
                       List<ChatRequest.Message> history, List<String> rememberedImageBase64Urls,
                       String rememberedImageSummary) {
        this.userId = userId;
        this.text = text;
        this.imageBase64Urls = imageBase64Urls != null ? List.copyOf(imageBase64Urls) : List.of();
        this.history = history != null ? List.copyOf(history) : List.of();
        this.rememberedImageBase64Urls = rememberedImageBase64Urls != null ? List.copyOf(rememberedImageBase64Urls) : List.of();
        this.rememberedImageSummary = rememberedImageSummary;
    }

    public String userId() { return userId; }
    public String text() { return text; }
    public List<String> imageBase64Urls() { return imageBase64Urls; }
    public List<ChatRequest.Message> history() { return history; }
    public List<String> rememberedImageBase64Urls() { return rememberedImageBase64Urls; }
    public String rememberedImageSummary() { return rememberedImageSummary; }
}
