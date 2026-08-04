package com.youkeda.project.wechatproject.bot.model;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;

import java.util.List;

public class UserRequest {

    private final String userId;
    private final String text;
    private final List<String> imageBase64Urls;
    private final List<ChatRequest.Message> history;
    private final List<String> rememberedImageBase64Urls;
    private final String rememberedImageSummary;
    private final ContextStage contextStage;
    private final ContextTaskState taskState;

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
        this.contextStage = ContextStage.PLAN;
        this.taskState = ContextTaskState.empty();
    }

    public UserRequest(String userId, String text, List<String> imageBase64Urls,
                       List<ChatRequest.Message> history, List<String> rememberedImageBase64Urls,
                       String rememberedImageSummary, ContextStage contextStage,
                       ContextTaskState taskState) {
        this.userId = userId;
        this.text = text;
        this.imageBase64Urls = imageBase64Urls != null ? List.copyOf(imageBase64Urls) : List.of();
        this.history = history != null ? List.copyOf(history) : List.of();
        this.rememberedImageBase64Urls = rememberedImageBase64Urls != null
                ? List.copyOf(rememberedImageBase64Urls) : List.of();
        this.rememberedImageSummary = rememberedImageSummary;
        this.contextStage = contextStage != null ? contextStage : ContextStage.PLAN;
        this.taskState = taskState != null ? taskState : ContextTaskState.empty();
    }

    public String userId() { return userId; }
    public String text() { return text; }
    public List<String> imageBase64Urls() { return imageBase64Urls; }
    public List<ChatRequest.Message> history() { return history; }
    public List<String> rememberedImageBase64Urls() { return rememberedImageBase64Urls; }
    public String rememberedImageSummary() { return rememberedImageSummary; }
    public ContextStage contextStage() { return contextStage; }
    public ContextTaskState taskState() { return taskState; }
}
