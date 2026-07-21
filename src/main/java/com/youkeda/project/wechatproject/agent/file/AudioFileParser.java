package com.youkeda.project.wechatproject.agent.file;

import com.youkeda.project.wechatproject.agent.speech.SpeechToTextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 音频文件解析器。通过 STT 将音频转为文本。
 */
public class AudioFileParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(AudioFileParser.class);

    private static final Map<String, String> EXTENSION_TO_FORMAT = Map.of(
            "mp3", "mp3",
            "wav", "wav",
            "m4a", "m4a",
            "ogg", "ogg",
            "flac", "flac",
            "wma", "wma",
            "aac", "aac",
            "opus", "opus"
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = EXTENSION_TO_FORMAT.keySet();

    private final SpeechToTextClient sttClient;

    public AudioFileParser(SpeechToTextClient sttClient) {
        this.sttClient = sttClient;
    }

    @Override
    public boolean supports(String fileExtension) {
        return SUPPORTED_EXTENSIONS.contains(fileExtension);
    }

    @Override
    public FileParseResult parse(byte[] bytes, String fileName) throws IOException {
        String extension = FileParserRegistry.extractExtension(fileName);
        String format = EXTENSION_TO_FORMAT.getOrDefault(extension, extension);

        log.info("audio file parsing: '{}' -> {} KB, format={}", fileName, bytes.length / 1024, format);
        String transcript = sttClient.recognize(bytes, format);
        log.info("audio transcription: {} chars", transcript != null ? transcript.length() : 0);

        if (transcript == null || transcript.isBlank()) {
            return new FileParseResult("[音频文件，未能识别出文字内容]", List.of(), fileName);
        }

        return new FileParseResult(transcript, List.of(), fileName);
    }
}
