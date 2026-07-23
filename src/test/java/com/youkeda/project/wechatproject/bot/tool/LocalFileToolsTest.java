package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileToolsTest {

    @TempDir
    private Path tempDir;

    @AfterEach
    void clearPreparedFile() {
        LocalFileTools.getAndClearPreparedFile();
    }

    @Test
    void exposesToolAnnotations() throws NoSuchMethodException {
        Method search = LocalFileTools.class.getMethod("searchLocalFiles",
                String.class, String.class, String.class, Integer.class);
        Method read = LocalFileTools.class.getMethod("readLocalTextFile", String.class, Integer.class);
        Method send = LocalFileTools.class.getMethod("sendLocalFile", String.class);

        assertThat(search.getAnnotation(Tool.class).name()).isEqualTo("search_local_files");
        assertThat(read.getAnnotation(Tool.class).name()).isEqualTo("read_local_text_file");
        assertThat(send.getAnnotation(Tool.class).name()).isEqualTo("send_local_file");
    }

    @Test
    void searchesAllowedFilesByName() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Files.writeString(allowed.resolve("weekly-report.txt"), "hello", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.searchLocalFiles("report", null, "*.txt", 10);

        assertThat(result).contains("weekly-report.txt");
        assertThat(result).contains("read_local_text_file");
    }

    @Test
    void skipsExcludedDirectoriesDuringSearch() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Files.writeString(Files.createDirectories(allowed.resolve("target")).resolve("hidden-report.txt"),
                "hidden", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.searchLocalFiles("hidden-report", null, null, 10);

        assertThat(result).contains("未找到匹配");
    }

    @Test
    void readsTextPreviewInsideAllowedRoot() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path file = allowed.resolve("notes.md");
        Files.writeString(file, "# Notes\nhello local file", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.readLocalTextFile(file.toString(), null);

        assertThat(result).contains("notes.md");
        assertThat(result).contains("hello local file");
    }

    @Test
    void rejectsPathOutsideAllowedRoot() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "nope", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.readLocalTextFile(outside.toString(), null);

        assertThat(result).contains("不在允许访问目录内");
    }

    @Test
    void rejectsSensitiveFileNames() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path secret = Files.writeString(allowed.resolve(".env"), "TOKEN=secret", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.readLocalTextFile(secret.toString(), null);

        assertThat(result).contains("疑似敏感文件");
    }

    @Test
    void preparesFilePayloadForFinalWechatDispatch() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path file = Files.writeString(allowed.resolve("paper.txt"), "send me", StandardCharsets.UTF_8);

        LocalFileTools tool = configuredTool(allowed);
        String result = tool.sendLocalFile(file.toString());
        LocalFileTools.PreparedFile payload = LocalFileTools.getAndClearPreparedFile();

        assertThat(result).contains("[LOCAL_FILE:");
        assertThat(payload).isNotNull();
        assertThat(payload.fileName()).isEqualTo("paper.txt");
        assertThat(new String(payload.bytes(), StandardCharsets.UTF_8)).isEqualTo("send me");
    }

    private static LocalFileTools configuredTool(Path allowedRoot) {
        LocalFileTools tool = new LocalFileTools();
        tool.setAllowedRoots(List.of(allowedRoot.toString()));
        tool.setMaxSearchDepth(5);
        tool.setMaxSearchResults(10);
        tool.setMaxReadBytes(1024);
        tool.setMaxSendFileBytes(1024 * 1024);
        return tool;
    }
}
