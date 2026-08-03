package com.youkeda.project.wechatproject.bot.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentServiceTests {

    @Test
    void registersDocumentAndTextParsers() {
        DocumentService documents = new DocumentService(null);

        assertThat(documents.isSupported("docx")).isTrue();
        assertThat(documents.isSupported("pdf")).isTrue();
        assertThat(documents.isSupported("txt")).isTrue();
        assertThat(documents.isSupported("yaml")).isTrue();
        assertThat(documents.isSupported("properties")).isTrue();
        assertThat(documents.isSupported("java")).isTrue();
    }

    @Test
    void parsesPlainTextWithoutOldParserClasses() throws IOException {
        DocumentService documents = new DocumentService(null);

        DocumentService.ParseResult result = documents.parse(
                "hello".getBytes(StandardCharsets.UTF_8),
                "note.txt");

        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.images()).isEmpty();
        assertThat(result.fileName()).isEqualTo("note.txt");
    }
}
