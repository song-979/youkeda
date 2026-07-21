package com.youkeda.project.wechatproject.agent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件解析器注册中心，按文件扩展名路由到对应的 {@link FileParser}。
 */
public class FileParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileParserRegistry.class);

    private final Map<String, FileParser> parsers = new LinkedHashMap<>();

    public FileParserRegistry(List<FileParser> parserList) {
        for (FileParser parser : parserList) {
            log.info("registering FileParser: {}", parser.getClass().getSimpleName());
        }
        this.parsers.putAll(buildExtensionMap(parserList));
    }

    private static Map<String, FileParser> buildExtensionMap(List<FileParser> parserList) {
        Map<String, FileParser> map = new LinkedHashMap<>();
        for (FileParser parser : parserList) {
            // 每个 parser 声明的扩展名通过 supports() 来匹配，
            // 但注册时需要显式声明支持哪些扩展名。
            // 这里通过尝试常见扩展名来构建映射。
            for (String ext : getExtensionsFor(parser)) {
                map.putIfAbsent(ext, parser);
            }
        }
        return map;
    }

    private static String[] getExtensionsFor(FileParser parser) {
        // 使用反射方式 — 每个 parser 实现 supports()，
        // 注册表通过预定义的扩展名列表来匹配
        String[] candidates = {
                "docx", "doc", "pdf", "txt", "csv", "json", "xml", "md", "log",
                "mp3", "wav", "m4a", "ogg", "flac", "wma", "aac", "opus"
        };
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (String ext : candidates) {
            if (parser.supports(ext)) {
                matched.add(ext);
            }
        }
        return matched.toArray(new String[0]);
    }

    /**
     * 是否支持该扩展名。
     */
    public boolean isSupported(String extension) {
        return parsers.containsKey(extension);
    }

    /**
     * 是否有注册的解析器。
     */
    public boolean isEmpty() {
        return parsers.isEmpty();
    }

    /**
     * 根据文件名解析文件内容。
     *
     * @param bytes    文件原始字节
     * @param fileName 原始文件名
     * @return 解析结果
     * @throws IOException 解析失败时抛出
     */
    public FileParseResult parse(byte[] bytes, String fileName) throws IOException {
        String extension = extractExtension(fileName);
        if (extension == null || extension.isBlank()) {
            throw new IOException("无法识别文件类型（无扩展名）: " + fileName);
        }

        FileParser parser = parsers.get(extension.toLowerCase());
        if (parser == null) {
            throw new IOException("不支持的文件类型: ." + extension);
        }

        log.info("parsing file '{}' ({} KB) with {}", fileName, bytes.length / 1024, parser.getClass().getSimpleName());
        return parser.parse(bytes, fileName);
    }

    public static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}
