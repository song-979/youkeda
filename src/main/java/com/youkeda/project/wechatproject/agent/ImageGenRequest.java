package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI Images API 请求体（DALL-E 兼容）。
 */
class ImageGenRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("prompt")
    private String prompt;

    @JsonProperty("n")
    private int n;

    @JsonProperty("size")
    private String size;

    @JsonProperty("response_format")
    private String responseFormat;

    ImageGenRequest() {
    }

    ImageGenRequest(String model, String prompt, int n, String size, String responseFormat) {
        this.model = model;
        this.prompt = prompt;
        this.n = n;
        this.size = size;
        this.responseFormat = responseFormat;
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public int getN() { return n; }
    public void setN(int n) { this.n = n; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
}
