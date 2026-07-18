package com.youkeda.project.wechatproject.agent;

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
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * OpenAI Images API 客户端（DALL-E 兼容）。
 * 调用文生图 API，下载生成的图片并返回字节数组。
 */
public class OpenAiImageGenClient implements ImageGenClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageGenClient.class);

    private final AgentProperties props;
    private final RestTemplate restTemplate;

    public OpenAiImageGenClient(AgentProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(props);
    }

    private static RestTemplate createRestTemplate(AgentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @Override
    public byte[] generate(String prompt) throws IOException {
        ImageGenRequest request = new ImageGenRequest(
                props.getImageGenModel(),
                prompt,
                props.getImageGenN(),
                props.getImageGenSize(),
                "url"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String key = props.getImageGenApiKey() != null && !props.getImageGenApiKey().isEmpty()
                ? props.getImageGenApiKey() : props.getApiKey();
        headers.setBearerAuth(key);

        HttpEntity<ImageGenRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<ImageGenResponse> response = restTemplate.postForEntity(
                    props.getImageGenApiUrl(), entity, ImageGenResponse.class);

            ImageGenResponse body = response.getBody();
            if (body == null) {
                throw new IOException("Image generation API returned empty response");
            }

            String imageUrl = body.extractUrl();
            if (imageUrl == null || imageUrl.isEmpty()) {
                throw new IOException("Image generation API returned no image URL");
            }

            log.info("image generated, downloading from url: {}", imageUrl);
            return downloadImage(imageUrl);

        } catch (RestClientException e) {
            log.error("Image generation API call failed: url={}, model={}, error={}",
                    props.getImageGenApiUrl(), props.getImageGenModel(), e.getMessage());
            throw new IOException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private byte[] downloadImage(String imageUrl) throws IOException {
        try (InputStream in = URI.create(imageUrl).toURL().openStream()) {
            return in.readAllBytes();
        }
    }
}
