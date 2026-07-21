package com.youkeda.project.wechatproject.agent.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文件解析器。提取文本和嵌入图片。
 */
public class PdfParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    private static final int MAX_IMAGE_COUNT = 30;
    private static final int MAX_TEXT_CHARS = 100_000;

    @Override
    public boolean supports(String fileExtension) {
        return "pdf".equals(fileExtension);
    }

    @Override
    public FileParseResult parse(byte[] bytes, String fileName) throws IOException {
        List<String> pageTexts = new ArrayList<>();
        List<byte[]> images = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(bytes);
             ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {

            int pageCount = doc.getNumberOfPages();
            log.info("pdf loading: '{}' -> {} pages", fileName, pageCount);

            // 提取文本
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(doc);
            if (fullText != null && !fullText.isBlank()) {
                if (fullText.length() > MAX_TEXT_CHARS) {
                    fullText = fullText.substring(0, MAX_TEXT_CHARS) + "\n\n...(文本过长已截断)";
                }
                pageTexts.add(fullText.trim());
            }

            // 提取嵌入图片
            for (int i = 0; i < pageCount && images.size() < MAX_IMAGE_COUNT; i++) {
                PDPage page = doc.getPage(i);
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    if (images.size() >= MAX_IMAGE_COUNT) {
                        break;
                    }
                    try {
                        if (resources.isImageXObject(name)) {
                            PDImageXObject img = (PDImageXObject) resources.getXObject(name);
                            try {
                                // 渲染为 BufferedImage 再编码为 PNG
                                java.awt.image.BufferedImage buffered = img.getImage();
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                javax.imageio.ImageIO.write(buffered, "png", baos);
                                byte[] imgBytes = baos.toByteArray();
                                if (imgBytes.length > 0) {
                                    images.add(imgBytes);
                                }
                            } catch (Exception e) {
                                log.debug("failed to extract PDF image: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("skipping PDF image on page {}: {}", i + 1, e.getMessage());
                    }
                }
            }
        }

        String text = String.join("\n\n", pageTexts);
        log.info("pdf parsed: '{}' -> {} chars, {} images", fileName, text.length(), images.size());
        return new FileParseResult(text, images, fileName);
    }
}
