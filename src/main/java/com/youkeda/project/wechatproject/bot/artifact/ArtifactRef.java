package com.youkeda.project.wechatproject.bot.artifact;

import java.time.Instant;
import java.util.Map;

/** Metadata safe to persist in DAG results and expose to orchestration context. */
public record ArtifactRef(
        String artifactId,
        String recipientId,
        String requestId,
        String runId,
        String nodeId,
        int revision,
        String producerAgent,
        ArtifactType type,
        ArtifactRole role,
        ArtifactStatus status,
        String fileName,
        String mimeType,
        long size,
        String sha256,
        String description,
        Map<String, String> attributes,
        Instant createdAt,
        Instant expiresAt) {

    public ArtifactRef {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public boolean isSendable() {
        return status == ArtifactStatus.VERIFIED
                && (role == ArtifactRole.FINAL_OUTPUT || role == ArtifactRole.USER_ACTION);
    }
}
