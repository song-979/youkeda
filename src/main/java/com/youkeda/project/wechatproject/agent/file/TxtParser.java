package com.youkeda.project.wechatproject.agent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 纯文本解析器。支持 .txt, .csv, .json, .xml, .md, .log 等文本格式。
 */
public class TxtParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(TxtParser.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "csv", "json", "xml", "md", "log",
            "yaml", "yml", "properties", "ini", "cfg", "conf",
            "html", "htm", "css", "js", "ts", "java", "py", "sh", "bat",
            "sql", "c", "cpp", "h", "hpp"
    );

    @Override
    public boolean supports(String fileExtension) {
        return SUPPORTED_EXTENSIONS.contains(fileExtension);
    }

    @Override
    public FileParseResult parse(byte[] bytes, String fileName) throws IOException {
        String text = decodeText(bytes);
        log.info("txt parsed: '{}' -> {} chars", fileName, text.length());
        return new FileParseResult(text, List.of(), fileName);
    }

    static String decodeText(byte[] bytes) {
        // 先尝试 UTF-8
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 检查是否有乱码特征（大量替换字符）
            if (!looksCorrupted(text)) {
                return text;
            }
        } catch (Exception ignored) {
        }

        // 降级 GBK
        try {
            String text = new String(bytes, Charset.forName("GBK"));
            log.debug("decoded as GBK: {} chars", text.length());
            return text;
        } catch (Exception e) {
            log.warn("GBK decode failed, falling back to UTF-8 with replacement");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean looksCorrupted(String text) {
        if (text.length() < 50) {
            return false;
        }
        int replacementCount = 0;
        for (int i = 0; i < Math.min(text.length(), 200); i++) {
            if (text.charAt(i) == '\uFFFD') {
                replacementCount++;
            }
        }
        return replacementCount > 3;
    }
}
