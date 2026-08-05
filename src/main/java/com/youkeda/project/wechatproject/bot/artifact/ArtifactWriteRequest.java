package com.youkeda.project.wechatproject.bot.artifact;

import java.time.Instant;
import java.util.Map;

public record ArtifactWriteRequest(
        String recipientId,
        String requestId,
        String runId,
        String nodeId,
        int revision,
        String producerAgent,
        ArtifactType type,
        ArtifactRole role,
        String fileName,
        String mimeType,
        byte[] bytes,
        String description,
        Map<String, String> attributes,
        Instant expiresAt) {

    public ArtifactWriteRequest {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }
}
