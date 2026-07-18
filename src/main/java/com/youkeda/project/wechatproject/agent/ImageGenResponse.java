package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Images API 响应体（DALL-E 兼容）。
 */
class ImageGenResponse {

    @JsonProperty("created")
    private long created;

    @JsonProperty("data")
    private List<ImageData> data;

    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }

    public List<ImageData> getData() { return data; }
    public void setData(List<ImageData> data) { this.data = data; }

    /**
     * 提取第一张图片的 URL，失败返回 null。
     */
    public String extractUrl() {
        if (data == null || data.isEmpty()) return null;
        ImageData first = data.get(0);
        return first != null ? first.getUrl() : null;
    }

    /**
     * 提取第一张图片的 base64 数据，失败返回 null。
     */
    public String extractB64Json() {
        if (data == null || data.isEmpty()) return null;
        ImageData first = data.get(0);
        return first != null ? first.getB64Json() : null;
    }

    static class ImageData {
        @JsonProperty("url")
        private String url;

        @JsonProperty("b64_json")
        private String b64Json;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getB64Json() { return b64Json; }
        public void setB64Json(String b64Json) { this.b64Json = b64Json; }
    }
}
