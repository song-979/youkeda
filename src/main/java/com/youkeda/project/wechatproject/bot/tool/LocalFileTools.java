package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ProjectTool;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "agent.tools.files")
@ConditionalOnProperty(prefix = "agent.tools.files", name = "enabled", havingValue = "true")
public class LocalFileTools implements ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(LocalFileTools.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ThreadLocal<PreparedFile> PREPARED_FILE = new ThreadLocal<>();
    private static final int MAX_EMBED_IMAGES = 5;
    private static final long MAX_EMBED_IMAGE_TOTAL_BYTES = 5 * 1024 * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.75f;

    @Autowired(required = false)
    private DocumentService documentService;

    private boolean enabled = false;
    private List<String> allowedRoots = new ArrayList<>(List.of("."));
    private List<String> excludedDirectories = new ArrayList<>(List.of(
            ".git", ".idea", ".gradle", ".mvn", "target", "build", "dist",
            "node_modules", "__pycache__"));
    private List<String> deniedFileNamePatterns = new ArrayList<>(List.of(
            ".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx",
            "id_rsa", "id_rsa.*", "id_ed25519", "id_ed25519.*",
            "application.properties", "application-*.properties",
            "application.yml", "application-*.yml", "bootstrap.properties", "bootstrap.yml"));
    private int maxSearchResults = 30;
    private int maxSearchDepth = 8;
    private long maxReadBytes = 200 * 1024L;
    private long maxSendFileBytes = 10 * 1024 * 1024L;

    @Override
    public String category() {
        return "local_files";
    }

    @Tool(name = "list_allowed_local_roots",
            description = "列出当前允许检索、读取和发送的本地文件根目录。访问本机文件前先用它确认边界。")
    public String listAllowedLocalRoots() {
        List<Path> roots = allowedRootPaths();
        if (roots.isEmpty()) {
            return "本地文件工具未配置可访问目录，请设置 agent.tools.files.allowed-roots。";
        }

        StringBuilder sb = new StringBuilder("允许访问的本地目录：\n");
        for (int i = 0; i < roots.size(); i++) {
            sb.append(i + 1).append(". ").append(roots.get(i)).append("\n");
        }
        sb.append("\n默认会跳过目录：").append(String.join(", ", excludedDirectories));
        sb.append("\n默认会拒绝读取/发送疑似密钥或配置文件。");
        return sb.toString().trim();
    }

    @Tool(name = "search_local_files",
            description = "按文件名或路径关键词检索本地文件。只会在允许目录内搜索，并跳过常见构建目录、依赖目录和敏感文件。")
    public String searchLocalFiles(
            @ToolParam(description = "文件名或路径关键词，例如 report、简历、Invoice。") String query,
            @ToolParam(required = false, description = "可选搜索根目录；必须在允许目录内。为空时搜索所有允许目录。") String root,
            @ToolParam(required = false, description = "可选 glob 过滤，例如 *.pdf、*.docx、**/*.java。") String glob,
            @ToolParam(required = false, description = "可选最大返回数量，默认使用配置值，最多 100。") Integer maxResults) {
        String effectiveQuery = query != null ? query.trim() : "";
        String effectiveGlob = glob != null ? glob.trim() : "";
        if (effectiveQuery.isEmpty() && effectiveGlob.isEmpty()) {
            return "请提供文件名关键词或 glob 过滤条件。";
        }

        List<Path> searchRoots;
        try {
            searchRoots = searchRoots(root);
        } catch (IOException | IllegalArgumentException e) {
            return "本地文件检索失败：" + e.getMessage();
        }
        if (searchRoots.isEmpty()) {
            return "本地文件工具未找到可搜索的允许目录。";
        }

        PathMatcher matcher;
        try {
            matcher = createGlobMatcher(effectiveGlob);
        } catch (IllegalArgumentException e) {
            return "glob 过滤条件无效：" + e.getMessage();
        }

        int limit = clamp(maxResults != null ? maxResults : maxSearchResults, 1, 100);
        List<SearchHit> hits = new ArrayList<>();
        boolean[] truncated = {false};

        for (Path searchRoot : searchRoots) {
            if (hits.size() >= limit) {
                truncated[0] = true;
                break;
            }
            collectMatches(searchRoot, effectiveQuery, matcher, limit, hits, truncated);
        }

        if (hits.isEmpty()) {
            return "未找到匹配的本地文件。";
        }
        return formatSearchHits(hits, truncated[0]);
    }

    @Tool(name = "read_local_text_file",
            description = "读取本地文件内容。支持 Word(.doc/.docx)、PDF(.pdf)、纯文本(.txt/.md/.json/.csv/.html/.xml/.log) 等格式。对于不支持的格式会自动退回二进制检测。")
    public String readLocalTextFile(
            @ToolParam(description = "要读取的本地文件路径，建议使用 search_local_files 返回的完整路径。") String path,
            @ToolParam(required = false, description = "可选最大读取字节数，默认使用配置值。") Integer maxBytes) {
        Path file;
        try {
            file = resolveAllowedFile(path);
        } catch (IOException | IllegalArgumentException e) {
            return "读取本地文件失败：" + e.getMessage();
        }

        try {
            long size = Files.size(file);
            String fileName = file.getFileName().toString();
            String ext = DocumentService.extractExtension(fileName);

            // 尝试用 DocumentService 解析已知文档格式
            if (documentService != null && documentService.isSupported(ext)) {
                return readWithDocumentService(file, fileName, ext, size);
            }

            // 未知格式，走原有的文本/二进制检测逻辑
            int byteLimit = (int) Math.min(Math.max(1, maxBytes != null ? maxBytes : maxReadBytes), maxReadBytes);
            byte[] bytes = readPrefix(file, byteLimit);

            if (looksBinary(bytes)) {
                return "文件可能是二进制格式，未展开内容。\n"
                        + "路径：" + file + "\n"
                        + "大小：" + humanSize(size) + "\n"
                        + "如需完整文件，请调用 send_local_file 发送。";
            }

            String text = decodeText(bytes);
            boolean truncated = size > bytes.length;
            StringBuilder sb = new StringBuilder();
            sb.append("文件：").append(file).append("\n");
            sb.append("大小：").append(humanSize(size)).append("\n");
            sb.append("修改时间：").append(formatModifiedTime(file)).append("\n");
            if (truncated) {
                sb.append("内容预览：前 ").append(humanSize(bytes.length)).append("，已截断。\n\n");
            } else {
                sb.append("内容：\n\n");
            }
            sb.append(text);
            return sb.toString().trim();
        } catch (IOException e) {
            log.error("read_local_text_file failed for path={}", file, e);
            return "读取本地文件失败：" + e.getMessage();
        }
    }

    private String readWithDocumentService(Path file, String fileName, String ext, long size) throws IOException {
        long parseLimit = Math.min(size, maxSendFileBytes);
        if (size > parseLimit) {
            return "文件过大（" + humanSize(size) + "），当前读取上限为 " + humanSize(maxSendFileBytes) + "。";
        }

        byte[] bytes = Files.readAllBytes(file);
        DocumentService.ParseResult result = documentService.parse(bytes, fileName);

        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(file).append("\n");
        sb.append("格式：").append(ext.toUpperCase()).append("\n");
        sb.append("大小：").append(humanSize(size)).append("\n");
        sb.append("修改时间：").append(formatModifiedTime(file)).append("\n");

        String text = result.text();
        if (text == null || text.isBlank()) {
            sb.append("\n文件内容为空或无法提取文字。");
        } else {
            sb.append("\n内容：\n\n").append(text);
        }

        List<byte[]> images = result.images();
        if (images != null && !images.isEmpty()) {
            sb.append("\n\n---\n");
            sb.append("文档中提取到 ").append(images.size()).append(" 张嵌入图片：\n");

            long totalBytes = 0;
            int embedCount = 0;
            for (int i = 0; i < images.size(); i++) {
                byte[] raw = images.get(i);
                if (raw == null || raw.length == 0) {
                    continue;
                }
                if (embedCount >= MAX_EMBED_IMAGES || totalBytes > MAX_EMBED_IMAGE_TOTAL_BYTES) {
                    sb.append("\n（还有 ").append(images.size() - embedCount)
                            .append(" 张图片因数量/大小限制未展开，已到达上限）");
                    break;
                }
                totalBytes += raw.length;
                try {
                    byte[] compressed = compressImageForEmbed(raw);
                    String dataUri = "data:image/jpeg;base64,"
                            + java.util.Base64.getEncoder().encodeToString(compressed);
                    sb.append("\n图片 ").append(i + 1).append("：\n").append(dataUri).append("\n");
                    embedCount++;
                } catch (Exception e) {
                    log.warn("failed to compress image {} from '{}'", i + 1, fileName, e);
                    sb.append("\n图片 ").append(i + 1).append("：（压缩失败，已跳过）");
                }
            }
        }

        return sb.toString().trim();
    }

    private static byte[] compressImageForEmbed(byte[] raw) throws IOException {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(raw));
        if (src == null) {
            return raw;
        }

        BufferedImage scaled = resizeIfNeeded(src);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ImageIO.write(scaled, "jpg", out);
            return out.toByteArray();
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(scaled, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage resizeIfNeeded(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_IMAGE_DIMENSION) {
            return src;
        }
        double ratio = (double) MAX_IMAGE_DIMENSION / max;
        int newW = Math.max(1, (int) (w * ratio));
        int newH = Math.max(1, (int) (h * ratio));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.drawImage(src.getScaledInstance(newW, newH, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    @Tool(name = "send_local_file",
            description = "准备把本地文件通过微信发送给用户。只允许发送白名单目录内的非敏感文件，并受大小限制。")
    public String sendLocalFile(
            @ToolParam(description = "要发送的本地文件路径，建议使用 search_local_files 返回的完整路径。") String path) {
        Path file;
        try {
            file = resolveAllowedFile(path);
        } catch (IOException | IllegalArgumentException e) {
            return "发送本地文件失败：" + e.getMessage();
        }

        try {
            long size = Files.size(file);
            if (size > maxSendFileBytes) {
                return "发送本地文件失败：文件过大（" + humanSize(size)
                        + "），当前上限为 " + humanSize(maxSendFileBytes) + "。";
            }

            byte[] bytes = Files.readAllBytes(file);
            PreparedFile payload = new PreparedFile(bytes, file.getFileName().toString(), file.toString());
            PREPARED_FILE.set(payload);

            return "[LOCAL_FILE:" + payload.absolutePath() + "]\n"
                    + "文件已准备发送："
                    + payload.fileName()
                    + "，大小 "
                    + humanSize(bytes.length)
                    + "。最终回复时会作为微信文件发出。";
        } catch (IOException e) {
            log.error("send_local_file failed for path={}", file, e);
            return "发送本地文件失败：" + e.getMessage();
        }
    }

    public static PreparedFile peekPreparedFile() {
        return PREPARED_FILE.get();
    }

    public static PreparedFile getAndClearPreparedFile() {
        PreparedFile file = PREPARED_FILE.get();
        PREPARED_FILE.remove();
        return file;
    }

    public record PreparedFile(byte[] bytes, String fileName, String absolutePath) {
        public PreparedFile {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("file bytes must not be empty");
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName must not be blank");
            }
            if (absolutePath == null || absolutePath.isBlank()) {
                throw new IllegalArgumentException("absolutePath must not be blank");
            }
        }
    }

    private void collectMatches(Path searchRoot, String query, PathMatcher matcher, int limit,
                                List<SearchHit> hits, boolean[] truncated) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(searchRoot) && isExcludedDirectoryName(dir.getFileName())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (searchRoot.relativize(dir).getNameCount() > maxSearchDepth) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (hits.size() >= limit) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || isDeniedFileName(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path realFile;
                    try {
                        realFile = file.toRealPath();
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isUnderAnyAllowedRoot(realFile, allowedRootPaths())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matchesQuery(searchRoot, realFile, normalizedQuery) && matchesGlob(searchRoot, realFile, matcher)) {
                        hits.add(new SearchHit(realFile, attrs.size(), attrs.lastModifiedTime().toInstant()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (exc instanceof AccessDeniedException) {
                        log.debug("skipping access denied path: {}", file);
                    } else {
                        log.debug("skipping unreadable path {}: {}", file, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("search root failed: root={}, error={}", searchRoot, e.getMessage());
        }
    }

    private List<Path> searchRoots(String root) throws IOException {
        if (root == null || root.isBlank()) {
            return allowedRootPaths();
        }

        Path resolved = resolveAllowedExistingPath(root, false);
        if (!Files.isDirectory(resolved)) {
            throw new IOException("搜索根路径不是目录：" + resolved);
        }
        return List.of(resolved);
    }

    private Path resolveAllowedFile(String path) throws IOException {
        Path resolved = resolveAllowedExistingPath(path, true);
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("路径不是普通文件：" + resolved);
        }
        return resolved;
    }

    private Path resolveAllowedExistingPath(String rawPath, boolean rejectDirectories) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("路径为空。");
        }

        List<Path> roots = allowedRootPaths();
        if (roots.isEmpty()) {
            throw new IOException("未配置可访问目录。");
        }

        Path candidate;
        try {
            Path input = Path.of(rawPath.trim());
            candidate = input.isAbsolute() ? input : roots.get(0).resolve(input);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径格式无效：" + rawPath);
        }

        Path real = candidate.toRealPath();
        if (!isUnderAnyAllowedRoot(real, roots)) {
            throw new AccessDeniedException("路径不在允许访问目录内：" + real);
        }
        if (containsExcludedDirectory(real, roots)) {
            throw new AccessDeniedException("路径位于被排除的目录内：" + real);
        }
        if (rejectDirectories && Files.isDirectory(real)) {
            throw new IOException("路径是目录，不是文件：" + real);
        }
        if (Files.isRegularFile(real) && isDeniedFileName(real.getFileName())) {
            throw new AccessDeniedException("疑似敏感文件，已拒绝访问：" + real.getFileName());
        }
        return real;
    }

    private List<Path> allowedRootPaths() {
        List<Path> roots = new ArrayList<>();
        for (String root : allowedRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(root.trim()).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    log.warn("allowed local file root does not exist: {}", path);
                    continue;
                }
                Path real = path.toRealPath();
                if (Files.isDirectory(real) && !roots.contains(real)) {
                    roots.add(real);
                }
            } catch (Exception e) {
                log.warn("invalid allowed local file root '{}': {}", root, e.getMessage());
            }
        }
        return roots;
    }

    private boolean isUnderAnyAllowedRoot(Path path, List<Path> roots) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsExcludedDirectory(Path path, List<Path> roots) {
        for (Path root : roots) {
            if (!path.startsWith(root)) {
                continue;
            }
            Path relative = root.relativize(path);
            for (Path segment : relative) {
                if (isExcludedDirectoryName(segment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedDirectoryName(Path name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toString().toLowerCase(Locale.ROOT);
        for (String excluded : excludedDirectories) {
            if (normalized.equals(excluded.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeniedFileName(Path fileNamePath) {
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        for (String pattern : deniedFileNamePatterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String normalizedPattern = pattern.toLowerCase(Locale.ROOT);
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
                if (matcher.matches(Path.of(fileName))) {
                    return true;
                }
            } catch (Exception e) {
                if (fileName.equals(normalizedPattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private PathMatcher createGlobMatcher(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    private boolean matchesQuery(Path searchRoot, Path file, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String absolutePath = file.toString().toLowerCase(Locale.ROOT);
        String relativePath = searchRoot.relativize(file).toString().toLowerCase(Locale.ROOT);
        return fileName.contains(normalizedQuery)
                || absolutePath.contains(normalizedQuery)
                || relativePath.contains(normalizedQuery);
    }

    private boolean matchesGlob(Path searchRoot, Path file, PathMatcher matcher) {
        if (matcher == null) {
            return true;
        }
        Path relative = searchRoot.relativize(file);
        return matcher.matches(file.getFileName()) || matcher.matches(relative);
    }

    private String formatSearchHits(List<SearchHit> hits, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 个本地文件");
        if (truncated) {
            sb.append("（结果已截断）");
        }
        sb.append("：\n");

        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append(i + 1).append(". ").append(hit.path()).append("\n")
                    .append("   大小：").append(humanSize(hit.size())).append("，修改时间：")
                    .append(TIME_FORMATTER.format(hit.modifiedAt().atZone(ZoneId.systemDefault()))).append("\n");
        }
        sb.append("\n如需查看内容可调用 read_local_text_file；如需发送完整文件可调用 send_local_file。");
        return sb.toString().trim();
    }

    private static byte[] readPrefix(Path file, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(maxBytes);
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int sampleSize = Math.min(bytes.length, 4096);
        if (sampleSize == 0) {
            return false;
        }

        int suspicious = 0;
        for (int i = 0; i < sampleSize; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            boolean commonWhitespace = b == '\n' || b == '\r' || b == '\t';
            if (!commonWhitespace && b < 0x20) {
                suspicious++;
            }
        }
        return suspicious > sampleSize / 10;
    }

    private static String decodeText(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!looksCorrupted(text)) {
                return text;
            }
        } catch (Exception ignored) {
        }

        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
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

    private static String formatModifiedTime(Path file) throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        return TIME_FORMATTER.format(modified.atZone(ZoneId.systemDefault()));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SearchHit(Path path, long size, Instant modifiedAt) {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots != null ? new ArrayList<>(allowedRoots) : new ArrayList<>();
    }

    public List<String> getExcludedDirectories() {
        return excludedDirectories;
    }

    public void setExcludedDirectories(List<String> excludedDirectories) {
        this.excludedDirectories = excludedDirectories != null ? new ArrayList<>(excludedDirectories) : new ArrayList<>();
    }

    public List<String> getDeniedFileNamePatterns() {
        return deniedFileNamePatterns;
    }

    public void setDeniedFileNamePatterns(List<String> deniedFileNamePatterns) {
        this.deniedFileNamePatterns = deniedFileNamePatterns != null ? new ArrayList<>(deniedFileNamePatterns) : new ArrayList<>();
    }

    public int getMaxSearchResults() {
        return maxSearchResults;
    }

    public void setMaxSearchResults(int maxSearchResults) {
        this.maxSearchResults = maxSearchResults;
    }

    public int getMaxSearchDepth() {
        return maxSearchDepth;
    }

    public void setMaxSearchDepth(int maxSearchDepth) {
        this.maxSearchDepth = maxSearchDepth;
    }

    public long getMaxReadBytes() {
        return maxReadBytes;
    }

    public void setMaxReadBytes(long maxReadBytes) {
        this.maxReadBytes = maxReadBytes;
    }

    public long getMaxSendFileBytes() {
        return maxSendFileBytes;
    }

    public void setMaxSendFileBytes(long maxSendFileBytes) {
        this.maxSendFileBytes = maxSendFileBytes;
    }
}
