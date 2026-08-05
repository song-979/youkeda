package com.youkeda.project.wechatproject.bot.artifact;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.tool.chat.LocalFileTools;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapDirectionTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Moves binary results out of AgentResult while preserving the Agent's textual response. */
public class ArtifactCollector {

    private static final Pattern FILE_MARKER = Pattern.compile(
            "\\[FILE:(.+?)]\\r?\\n(.*?)\\r?\\n\\[/FILE]", Pattern.DOTALL);
    private static final Pattern LOCAL_FILE_MARKER = Pattern.compile("\\[LOCAL_FILE:(.+?)]");
    private static final Pattern MOTOU_GIF_MARKER = Pattern.compile("\\[MOTOU_GIF:(.+?)]");

    private final ArtifactStore store;
    private final DocumentService documentService;

    public ArtifactCollector(ArtifactStore store, DocumentService documentService) {
        this.store = store;
        this.documentService = documentService;
    }

    public AgentResult collect(AgentResult result, CollectionContext context) throws IOException {
        if (result == null) return null;
        List<ArtifactRef> artifacts = new ArrayList<>(result.artifacts());
        Object output = result.output();
        String raw = result.rawOutput();
        String text = output instanceof String value ? value : raw;

        if (output instanceof GeneratedImage image) {
            artifacts.add(put(context, ArtifactType.IMAGE, ArtifactRole.FINAL_OUTPUT,
                    image.fileName(), image.mediaType(), image.bytes(), "generated image", Map.of()));
            output = null;
        } else if (output instanceof SpeechAgent.TtsOutput speech) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("format", speech.format());
            attributes.put("durationMs", String.valueOf(speech.durationMs()));
            attributes.put("sampleRate", String.valueOf(speech.sampleRate()));
            String format = nonBlank(speech.format(), "wav").toLowerCase(Locale.ROOT);
            artifacts.add(put(context, ArtifactType.AUDIO, ArtifactRole.FINAL_OUTPUT,
                    "speech." + format, audioMimeType(format), speech.audioBytes(),
                    "generated speech", attributes));
            output = null;
        } else if (output instanceof byte[] bytes && "IMAGE_GEN".equals(context.producerAgent())) {
            artifacts.add(put(context, ArtifactType.IMAGE, ArtifactRole.FINAL_OUTPUT,
                    "generated.png", "image/png", bytes, "generated image", Map.of()));
            output = null;
        }

        boolean localFileExpected = false;
        if (text != null) {
            ParsedText parsed = collectTextArtifacts(text, context, artifacts);
            text = parsed.text();
            localFileExpected = parsed.localFileExpected();
            if (result.output() instanceof String) output = text;
            raw = text;
        }

        LocalFileTools.PreparedFile preparedFile = LocalFileTools.getAndClearPreparedFile();
        if (localFileExpected && preparedFile == null) {
            throw new IOException("local file marker has no correlated prepared file");
        }
        if (preparedFile != null) {
            artifacts.add(put(context, ArtifactType.FILE, ArtifactRole.FINAL_OUTPUT,
                    preparedFile.fileName(), mimeType(preparedFile.fileName()), preparedFile.bytes(),
                    "local file selected by agent", Map.of("sourcePath", preparedFile.absolutePath())));
        }

        int mapIndex = 0;
        for (byte[] bytes : AmapAroundSearchTools.drainMapImages()) {
            artifacts.add(put(context, ArtifactType.MAP, ArtifactRole.FINAL_OUTPUT,
                    "amap_around_" + (++mapIndex) + ".png", "image/png", bytes,
                    "nearby places map", Map.of()));
        }
        mapIndex = 0;
        for (byte[] bytes : AmapDirectionTools.drainMapImages()) {
            artifacts.add(put(context, ArtifactType.MAP, ArtifactRole.FINAL_OUTPUT,
                    "amap_route_" + (++mapIndex) + ".png", "image/png", bytes,
                    "route map", Map.of()));
        }

        int screenshotIndex = 0;
        for (ModelReply.ImagePayload image : result.pausedImages()) {
            artifacts.add(put(context, ArtifactType.SCREENSHOT, ArtifactRole.USER_ACTION,
                    safeName(image.fileName(), "action_" + (++screenshotIndex) + ".png"),
                    mimeType(image.fileName()), image.bytes(), "user action screenshot", Map.of()));
        }
        if (context.runId() != null && context.nodeId() != null) {
            store.expireOlderRevisions(context.runId(), context.nodeId(), context.revision());
        }
        return result.withMaterializedOutput(output, raw, artifacts);
    }

    public void clearThreadArtifacts() {
        LocalFileTools.getAndClearPreparedFile();
        AmapAroundSearchTools.drainMapImages();
        AmapDirectionTools.drainMapImages();
    }

    private ParsedText collectTextArtifacts(String value, CollectionContext context,
                                             List<ArtifactRef> artifacts) throws IOException {
        String text = value;
        Matcher fileMatcher = FILE_MARKER.matcher(text);
        StringBuffer remainder = new StringBuffer();
        while (fileMatcher.find()) {
            String fileName = safeName(fileMatcher.group(1).trim(), "generated.txt");
            String content = fileMatcher.group(2);
            byte[] bytes;
            String actualName = fileName;
            if (documentService != null) {
                DocumentService.GeneratedFile generated = documentService.generate(content, fileName);
                bytes = generated.bytes();
                actualName = generated.fileName();
            } else {
                bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            artifacts.add(put(context, ArtifactType.FILE, ArtifactRole.FINAL_OUTPUT,
                    actualName, mimeType(actualName), bytes, "file generated from agent output", Map.of()));
            fileMatcher.appendReplacement(remainder, "");
        }
        fileMatcher.appendTail(remainder);
        text = remainder.toString();

        Matcher gifMatcher = MOTOU_GIF_MARKER.matcher(text);
        remainder = new StringBuffer();
        while (gifMatcher.find()) {
            Path path = validateGeneratedPath(gifMatcher.group(1).trim());
            artifacts.add(put(context, ArtifactType.FILE, ArtifactRole.FINAL_OUTPUT,
                    safeName(path.getFileName().toString(), "motou.gif"), "image/gif",
                    Files.readAllBytes(path), "generated animation", Map.of("sourcePath", path.toString())));
            gifMatcher.appendReplacement(remainder, "");
        }
        gifMatcher.appendTail(remainder);
        Matcher localMatcher = LOCAL_FILE_MARKER.matcher(remainder.toString());
        boolean localFileExpected = localMatcher.find();
        text = localMatcher.replaceAll("");
        return new ParsedText(text.trim(), localFileExpected);
    }

    private ArtifactRef put(CollectionContext context, ArtifactType type, ArtifactRole role,
                            String fileName, String mimeType, byte[] bytes, String description,
                            Map<String, String> attributes) throws IOException {
        return store.put(new ArtifactWriteRequest(
                context.recipientId(), context.requestId(), context.runId(), context.nodeId(),
                context.revision(), context.producerAgent(), type, role, fileName, mimeType,
                bytes, description, attributes, null));
    }

    private static Path validateGeneratedPath(String rawPath) throws IOException {
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate)) throw new IOException("generated file does not exist: " + candidate);
        Path real = candidate.toRealPath();
        Path workspace = Path.of("").toAbsolutePath().normalize().toRealPath();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toRealPath();
        if (!real.startsWith(workspace) && !real.startsWith(temp)) {
            throw new IOException("generated file is outside allowed roots: " + real);
        }
        return real;
    }

    private static String mimeType(String fileName) {
        String name = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static String audioMimeType(String format) {
        return "mp3".equals(format) ? "audio/mpeg" : "audio/" + format;
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            Path name = Path.of(value).getFileName();
            return name != null && !name.toString().isBlank() ? name.toString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private record ParsedText(String text, boolean localFileExpected) {}

    public record CollectionContext(String recipientId, String requestId, String runId,
                                    String nodeId, int revision, String producerAgent) {}
}
