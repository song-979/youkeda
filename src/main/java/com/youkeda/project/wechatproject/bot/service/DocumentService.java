package com.youkeda.project.wechatproject.bot.service;

import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechToTextClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Document and file service.
 * <p>
 * Combines file type routing, document parsing, audio-file transcription, and
 * generated-file creation behind one service entry point.
 */
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final Map<String, Parser> parsers = new LinkedHashMap<>();

    public DocumentService(SpeechToTextClient sttClient) {
        register(new WordParser());
        register(new PdfParser());
        register(new TxtParser());
        if (sttClient != null) {
            register(new AudioParser(sttClient));
            log.info("Audio file parser registered (STT available)");
        } else {
            log.info("Audio file parser skipped (no STT client available)");
        }
    }

    private void register(Parser parser) {
        log.info("registering document parser: {}", parser.getClass().getSimpleName());
        for (String extension : parser.supportedExtensions()) {
            if (extension == null || extension.isBlank()) {
                continue;
            }
            parsers.putIfAbsent(extension.toLowerCase(Locale.ROOT), parser);
        }
    }

    public boolean isSupported(String extension) {
        return extension != null && parsers.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return parsers.isEmpty();
    }

    public ParseResult parse(byte[] bytes, String fileName) throws IOException {
        String extension = extractExtension(fileName);
        if (extension == null || extension.isBlank()) {
            throw new IOException("无法识别文件类型（无扩展名）: " + fileName);
        }

        Parser parser = parsers.get(extension.toLowerCase(Locale.ROOT));
        if (parser == null) {
            throw new IOException("不支持的文件类型: ." + extension);
        }

        log.info("parsing file '{}' ({} KB) with {}", fileName, bytes.length / 1024, parser.getClass().getSimpleName());
        return parser.parse(bytes, fileName);
    }

    public byte[] generate(String content, String fileName) {
        String ext = extension(fileName);
        return switch (ext) {
            case "docx" -> generateDocx(content);
            default -> content.getBytes(StandardCharsets.UTF_8);
        };
    }

    public static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    static String extension(String fileName) {
        String extension = extractExtension(fileName);
        return extension != null ? extension : "txt";
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
            log.info("docx generated: {} paragraphs", content.split("\n").length);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("failed to generate docx, falling back to plain text", e);
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record ParseResult(String text, List<byte[]> images, String fileName) {
        public ParseResult {
            if (images == null) {
                images = List.of();
            }
        }
    }

    private interface Parser {
        Set<String> supportedExtensions();

        default boolean supports(String fileExtension) {
            if (fileExtension == null || fileExtension.isBlank()) {
                return false;
            }
            return supportedExtensions().contains(fileExtension.toLowerCase(Locale.ROOT));
        }

        ParseResult parse(byte[] bytes, String fileName) throws IOException;
    }

    private static class TxtParser implements Parser {

        private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
                "txt", "csv", "json", "xml", "md", "log",
                "yaml", "yml", "properties", "ini", "cfg", "conf",
                "html", "htm", "css", "js", "ts", "java", "py", "sh", "bat",
                "sql", "c", "cpp", "h", "hpp"
        );

        @Override
        public Set<String> supportedExtensions() {
            return SUPPORTED_EXTENSIONS;
        }

        @Override
        public ParseResult parse(byte[] bytes, String fileName) {
            String text = decodeText(bytes);
            log.info("txt parsed: '{}' -> {} chars", fileName, text.length());
            return new ParseResult(text, List.of(), fileName);
        }

        static String decodeText(byte[] bytes) {
            try {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (!looksCorrupted(text)) {
                    return text;
                }
            } catch (Exception ignored) {
            }

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

    private static class WordParser implements Parser {

        private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("docx", "doc");

        /** OLE2 compound document magic bytes: D0 CF 11 E0 A1 B1 1A E1 */
        private static final byte[] OLE2_MAGIC = {
                (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
        };

        @Override
        public Set<String> supportedExtensions() {
            return SUPPORTED_EXTENSIONS;
        }

        private static boolean isOle2Format(byte[] bytes) {
            if (bytes == null || bytes.length < 8) {
                return false;
            }
            for (int i = 0; i < 8; i++) {
                if (bytes[i] != OLE2_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ParseResult parse(byte[] bytes, String fileName) throws IOException {
            if (isOle2Format(bytes)) {
                return parseOldDoc(bytes, fileName);
            }
            return parseDocx(bytes, fileName);
        }

        private ParseResult parseOldDoc(byte[] bytes, String fileName) throws IOException {
            List<String> paragraphs = new ArrayList<>();
            List<byte[]> images = new ArrayList<>();

            try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes))) {
                Range range = doc.getRange();
                int numParagraphs = range.numParagraphs();
                for (int i = 0; i < numParagraphs; i++) {
                    Paragraph para = range.getParagraph(i);
                    String text = para.text();
                    if (text != null && !text.isBlank()) {
                        // HWPF paragraphs may include the \r character at the end
                        text = text.replace('\r', '\n').replace("\n\n", "\n").trim();
                        if (!text.isEmpty()) {
                            paragraphs.add(text);
                        }
                    }
                }

                // Extract embedded pictures from the pictures table
                List<Picture> pictures = doc.getPicturesTable().getAllPictures();
                for (Picture pic : pictures) {
                    byte[] picBytes = pic.getContent();
                    if (picBytes != null && picBytes.length > 0) {
                        images.add(picBytes);
                    }
                }
            }

            String text = String.join("\n", paragraphs);
            log.info("old doc parsed: '{}' -> {} paragraphs, {} images", fileName, paragraphs.size(), images.size());
            return new ParseResult(text, images, fileName);
        }

        private ParseResult parseDocx(byte[] bytes, String fileName) throws IOException {
            List<String> paragraphs = new ArrayList<>();
            List<byte[]> images = new ArrayList<>();

            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                doc.getParagraphs().forEach(p -> {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        paragraphs.add(text);
                    }
                });

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
            return new ParseResult(text, images, fileName);
        }
    }

    private static class PdfParser implements Parser {

        private static final int MAX_IMAGE_COUNT = 30;
        private static final int MAX_TEXT_CHARS = 100_000;
        private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

        @Override
        public Set<String> supportedExtensions() {
            return SUPPORTED_EXTENSIONS;
        }

        @Override
        public ParseResult parse(byte[] bytes, String fileName) throws IOException {
            List<String> pageTexts = new ArrayList<>();
            List<byte[]> images = new ArrayList<>();

            try (PDDocument doc = Loader.loadPDF(bytes);
                 ByteArrayInputStream ignored = new ByteArrayInputStream(bytes)) {

                int pageCount = doc.getNumberOfPages();
                log.info("pdf loading: '{}' -> {} pages", fileName, pageCount);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                String fullText = stripper.getText(doc);
                if (fullText != null && !fullText.isBlank()) {
                    if (fullText.length() > MAX_TEXT_CHARS) {
                        fullText = fullText.substring(0, MAX_TEXT_CHARS) + "\n\n...(文本过长已截断)";
                    }
                    pageTexts.add(fullText.trim());
                }

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
                                    java.awt.image.BufferedImage buffered = img.getImage();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
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
            return new ParseResult(text, images, fileName);
        }
    }

    private static class AudioParser implements Parser {

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

        private final SpeechToTextClient sttClient;

        AudioParser(SpeechToTextClient sttClient) {
            this.sttClient = sttClient;
        }

        @Override
        public Set<String> supportedExtensions() {
            return EXTENSION_TO_FORMAT.keySet();
        }

        @Override
        public ParseResult parse(byte[] bytes, String fileName) throws IOException {
            String extension = extractExtension(fileName);
            String format = EXTENSION_TO_FORMAT.getOrDefault(extension, extension);

            log.info("audio file parsing: '{}' -> {} KB, format={}", fileName, bytes.length / 1024, format);
            String transcript = sttClient.recognize(bytes, format);
            log.info("audio transcription: {} chars", transcript != null ? transcript.length() : 0);

            if (transcript == null || transcript.isBlank()) {
                return new ParseResult("[音频文件，未能识别出文字内容]", List.of(), fileName);
            }

            return new ParseResult(transcript, List.of(), fileName);
        }
    }
}
