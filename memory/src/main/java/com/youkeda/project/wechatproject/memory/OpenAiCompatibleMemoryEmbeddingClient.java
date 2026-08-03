package com.youkeda.project.wechatproject.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleMemoryEmbeddingClient implements MemoryEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleMemoryEmbeddingClient.class);

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleMemoryEmbeddingClient(MemoryProperties props) {
        this.apiUrl = firstNonBlank(props.getEmbeddingApiUrl(), deriveEmbeddingUrl(props.getFallbackApiUrl()));
        this.apiKey = firstNonBlank(props.getEmbeddingApiKey(), props.getFallbackApiKey());
        this.model = firstNonBlank(props.getEmbeddingModel(), "text-embedding-v4");
        this.restTemplate = createRestTemplate(props);
    }

    private static RestTemplate createRestTemplate(MemoryProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @Override
    public double[] embed(String text) throws IOException {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IOException("embedding API URL is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("embedding API key is not configured");
        }
        if (text == null || text.isBlank()) {
            return new double[0];
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<EmbeddingResponse> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(request, headers), EmbeddingResponse.class);
            EmbeddingResponse body = response.getBody();
            double[] embedding = body != null ? body.firstEmbedding() : null;
            if (embedding == null || embedding.length == 0) {
                throw new IOException("embedding API returned no vector");
            }
            return embedding;
        } catch (RestClientException e) {
            log.warn("embedding API call failed: url={}, model={}, error={}", apiUrl, model, e.getMessage());
            throw new IOException("embedding API unavailable: " + e.getMessage(), e);
        }
    }

    private static String deriveEmbeddingUrl(String chatUrl) {
        if (chatUrl == null || chatUrl.isBlank()) {
            return null;
        }
        if (chatUrl.endsWith("/chat/completions")) {
            return chatUrl.substring(0, chatUrl.length() - "/chat/completions".length()) + "/embeddings";
        }
        if (chatUrl.endsWith("/chat/completions/")) {
            return chatUrl.substring(0, chatUrl.length() - "/chat/completions/".length()) + "/embeddings";
        }
        return chatUrl;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    public static class EmbeddingResponse {
        @JsonProperty("data")
        private List<EmbeddingData> data;

        public List<EmbeddingData> getData() { return data; }
        public void setData(List<EmbeddingData> data) { this.data = data; }

        double[] firstEmbedding() {
            if (data == null || data.isEmpty() || data.getFirst() == null || data.getFirst().embedding == null) {
                return null;
            }
            List<Double> values = data.getFirst().embedding;
            double[] embedding = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                embedding[i] = values.get(i);
            }
            return embedding;
        }
    }

    public static class EmbeddingData {
        @JsonProperty("embedding")
        private List<Double> embedding;

        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    }
}
