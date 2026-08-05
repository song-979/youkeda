package com.youkeda.project.wechatproject.bot.artifact;

import com.youkeda.project.wechatproject.bot.model.ModelReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Preserves model text and materializes only correlated, verified artifacts at delivery time. */
public class ReplyAssembler {

    private static final Logger log = LoggerFactory.getLogger(ReplyAssembler.class);
    private final ArtifactStore store;

    public ReplyAssembler(ArtifactStore store) {
        this.store = store;
    }

    public List<ModelReply> assemble(String text, List<ArtifactRef> references,
                                     String recipientId, String runId, Set<ArtifactRole> roles) {
        List<ModelReply> replies = new ArrayList<>();
        if (text != null && !text.isBlank()) replies.add(ModelReply.text(text));
        Set<ArtifactRole> acceptedRoles = roles != null && !roles.isEmpty()
                ? EnumSet.copyOf(roles) : EnumSet.of(ArtifactRole.FINAL_OUTPUT);
        for (ArtifactRef supplied : references != null ? references : List.<ArtifactRef>of()) {
            try {
                ArtifactRef ref = store.find(supplied.artifactId()).orElse(null);
                if (!isCorrelated(ref, recipientId, runId, acceptedRoles)) continue;
                byte[] bytes = store.read(ref.artifactId());
                replies.add(toReply(ref, bytes));
            } catch (Exception e) {
                log.warn("artifact delivery skipped id={} error={}", supplied.artifactId(), e.getMessage());
            }
        }
        return List.copyOf(replies);
    }

    private static boolean isCorrelated(ArtifactRef ref, String recipientId, String runId,
                                        Set<ArtifactRole> acceptedRoles) {
        if (ref == null || ref.status() != ArtifactStatus.VERIFIED || !acceptedRoles.contains(ref.role())) {
            return false;
        }
        if (ref.expiresAt() != null && !ref.expiresAt().isAfter(java.time.Instant.now())) return false;
        return Objects.equals(recipientId, ref.recipientId()) && Objects.equals(runId, ref.runId());
    }

    private static ModelReply toReply(ArtifactRef ref, byte[] bytes) {
        return switch (ref.type()) {
            case IMAGE, SCREENSHOT, MAP -> ModelReply.image(bytes, ref.fileName());
            case AUDIO -> ModelReply.voice(bytes,
                    ref.attributes().getOrDefault("format", extension(ref.fileName(), "wav")),
                    integer(ref.attributes().get("durationMs")), integer(ref.attributes().get("sampleRate")));
            case FILE -> ModelReply.file(bytes, ref.fileName());
        };
    }

    private static int integer(String value) {
        try { return value != null ? Integer.parseInt(value) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static String extension(String fileName, String fallback) {
        int dot = fileName != null ? fileName.lastIndexOf('.') : -1;
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : fallback;
    }
}
