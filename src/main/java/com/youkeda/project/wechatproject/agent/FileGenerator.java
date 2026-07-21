package com.youkeda.project.wechatproject.agent;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文件生成工具：将文本内容转换为文件字节。
 * <p>
 * 纯文本类格式（txt/md/json/csv/html/xml/log）直接按 UTF-8 编码；
 * .docx 使用 Apache POI 生成 Word 文档。
 */
public class FileGenerator {

    private static final Logger log = LoggerFactory.getLogger(FileGenerator.class);

    public byte[] generate(String content, String fileName) {
        String ext = extension(fileName);
        return switch (ext) {
            case "docx" -> generateDocx(content);
            default -> content.getBytes(StandardCharsets.UTF_8);
        };
    }

    private byte[] generateDocx(String content) {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String line : content.split("\n")) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
                run.setFontFamily("Microsoft YaHei");
                run.setFontSize(11);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            doc.close();
            log.info("docx generated: {} paragraphs", content.split("\n").length);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("failed to generate docx, falling back to plain text", e);
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    static String extension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "txt";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "txt";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
