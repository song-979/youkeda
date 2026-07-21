package com.youkeda.project.wechatproject.agent.file;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Word (.docx) 文件解析器。提取段落文本和嵌入图片。
 */
public class WordParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(WordParser.class);

    @Override
    public boolean supports(String fileExtension) {
        return "docx".equals(fileExtension) || "doc".equals(fileExtension);
    }

    @Override
    public FileParseResult parse(byte[] bytes, String fileName) throws IOException {
        List<String> paragraphs = new ArrayList<>();
        List<byte[]> images = new ArrayList<>();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 提取段落文本
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    paragraphs.add(text);
                }
            });

            // 提取嵌入图片
            List<XWPFPictureData> pictures = doc.getAllPictures();
            for (XWPFPictureData pic : pictures) {
                byte[] picBytes = pic.getData();
                if (picBytes != null && picBytes.length > 0) {
                    images.add(picBytes);
                }
            }
        }

        String text = String.join("\n", paragraphs);
        log.info("docx parsed: '{}' -> {} paragraphs, {} images", fileName, paragraphs.size(), images.size());
        return new FileParseResult(text, images, fileName);
    }
}
