package com.youkeda.project.wechatproject.bot.artifact;

import com.youkeda.project.wechatproject.bot.model.ModelReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexedArtifactStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void storesPayloadOutsideContextAndLoadsOnlyForCorrelatedRecipient() throws Exception {
        Path blobs = tempDir.resolve("blobs");
        IndexedArtifactStore store = new IndexedArtifactStore(
                tempDir.resolve("index.db"), blobs, new ArtifactValidator());
        ArtifactRef ref = store.put(request(1, pngBytes()));

        assertArrayEquals(pngBytes(), store.read(ref.artifactId()));
        assertEquals(1, store.findByRun("run-1").size());

        ReplyAssembler assembler = new ReplyAssembler(store);
        List<ModelReply> replies = assembler.assemble("done", List.of(ref), "user-1", "run-1",
                EnumSet.of(ArtifactRole.FINAL_OUTPUT));
        assertEquals(List.of(ModelReply.Type.TEXT, ModelReply.Type.IMAGE),
                replies.stream().map(ModelReply::getType).toList());

        List<ModelReply> wrongRecipient = assembler.assemble("done", List.of(ref), "user-2", "run-1",
                EnumSet.of(ArtifactRole.FINAL_OUTPUT));
        assertEquals(1, wrongRecipient.size());
        assertEquals(ModelReply.Type.TEXT, wrongRecipient.getFirst().getType());
    }

    @Test
    void expiresOlderNodeRevisionsAndRejectsCorruptedPayload() throws Exception {
        Path blobs = tempDir.resolve("blobs");
        IndexedArtifactStore store = new IndexedArtifactStore(
                tempDir.resolve("index.db"), blobs, new ArtifactValidator());
        ArtifactRef old = store.put(request(1, pngBytes()));
        store.put(request(2, pngBytes()));

        store.expireOlderRevisions("run-1", "node-1", 2);
        assertEquals(ArtifactStatus.EXPIRED, store.find(old.artifactId()).orElseThrow().status());

        Path payload;
        try (var paths = Files.walk(blobs)) {
            payload = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(old.artifactId()))
                    .findFirst().orElseThrow();
        }
        Files.write(payload, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> store.read(old.artifactId()));
    }

    private static ArtifactWriteRequest request(int revision, byte[] bytes) {
        return new ArtifactWriteRequest("user-1", "request-1", "run-1", "node-1", revision,
                "IMAGE_GEN", ArtifactType.IMAGE, ArtifactRole.FINAL_OUTPUT,
                "generated.png", "image/png", bytes, "test", Map.of(), null);
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
