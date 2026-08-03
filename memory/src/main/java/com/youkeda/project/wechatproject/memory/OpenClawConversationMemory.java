package com.youkeda.project.wechatproject.memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenClaw-style file memory.
 *
 * <p>Persistent memory is plain Markdown on disk:
 * <ul>
 *   <li>{@code MEMORY.md}: compact, curated durable facts, preferences, decisions, and action boundaries.</li>
 *   <li>{@code memory/YYYY-MM-DD.md}: append-only daily context loaded during bootstrap.</li>
 *   <li>{@code DREAMS.md}: review surface for dreaming/consolidation candidates loaded during bootstrap.</li>
 * </ul>
 *
 * <p>The in-process message deque only represents the active session window. It is not durable memory.
 */
public class OpenClawConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(OpenClawConversationMemory.class);

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern QUOTED_CONTENT =
            Pattern.compile("[\"'\\u201c\\u201d\\u2018\\u2019](.+?)[\"'\\u201c\\u201d\\u2018\\u2019]");
    private static final Pattern MEMORY_SAVE_PHRASE = Pattern.compile(
            "(记[入到]|存[入到]|保[存到]|加[入到])?长期记忆([中里]|的\\S{0,5})?|长期记住|请|帮我|把|将|remember that|(?i)remember that|[，。]");
    private static final Pattern ASSISTANT_MEMORY_PATTERN_1 = Pattern.compile(
            "你的[^：:]{0,30}[：:]\\s*(.+?)(?:以后|下次|自动|～|。|！|$)");
    private static final Pattern ASSISTANT_MEMORY_PATTERN_2 = Pattern.compile(
            "[：:]\\s*([^：:。！\\n]{4,80})");

    private static final String SEC_PREFERENCES = "Preferences";
    private static final String SEC_PROJECTS = "Projects";
    private static final String SEC_DECISIONS = "Decisions";
    private static final String SEC_ACTION_BOUNDARIES = "Action-sensitive boundaries";
    private static final String SEC_FACTS = "Facts";

    private static final String DAILY_SUMMARY = "Daily summary";
    private static final String DAILY_SESSION_EVENTS = "Session events";
    private static final String DAILY_OBSERVATIONS = "Observations";
    private static final String DAILY_COMMITMENTS = "Commitments";
    private static final String DAILY_CUSTOM = "Custom notes";

    private static final int MAX_BOOTSTRAP_CHARS = 8_000;
    private static final int MAX_DAILY_NOTE_CHARS = 1_200;
    private static final int MAX_RESULT_CHARS = 300;
    private static final int MAX_TOPIC_CHARS = 50;
    private static final int MAX_SUMMARY_OUTCOME_CHARS = 100;
    private static final int DREAM_MIN_SIGNAL_CHARS = 8;
    private static final int MAX_WEEKLY_SUMMARY_CHARS = 3_000;
    private static final String WEEKLY_DIR = "weekly";
    private static final Pattern WEEKLY_SUMMARY_FILE = Pattern.compile("(\\d{4})-W(\\d{2})\\.md");

    private final int maxMessages;
    private final long ttlMillis;
    private final Path basePath;
    private final int dailyRetentionDays;
    private final ZoneId zoneId;
    private final VectorMemoryIndex vectorIndex;
    private final Map<String, SessionSlot> sessionStore = new ConcurrentHashMap<>(64);
    private final Map<String, Object> userFileLocks = new ConcurrentHashMap<>(64);
    private final Map<String, FileCacheEntry> fileCache = new ConcurrentHashMap<>(64);
    private final ScheduledExecutorService cacheCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "openclaw-cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    {
        cacheCleaner.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            fileCache.entrySet().removeIf(e -> now - e.getValue().cachedAt > 120_000);
        }, 60, 60, TimeUnit.SECONDS);
    }

    public OpenClawConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays) {
        this(maxHistoryRounds, memoryTtlMinutes, basePath, dailyRetentionDays, null);
    }

    public OpenClawConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays,
                                      VectorMemoryIndex vectorIndex) {
        this.maxMessages = Math.max(0, maxHistoryRounds) * 2;
        this.ttlMillis = Math.max(0L, memoryTtlMinutes) * 60_000L;
        this.basePath = Path.of(isBlank(basePath) ? "data/memory" : basePath).toAbsolutePath().normalize();
        this.dailyRetentionDays = Math.max(2, dailyRetentionDays);
        this.zoneId = ZoneId.systemDefault();
        this.vectorIndex = vectorIndex;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create OpenClaw memory directory: " + this.basePath, e);
        }
    }

    @Override
    public List<MemoryMessage> getHistory(String userId) {
        return getHistory(userId, null);
    }

    @Override
    public List<MemoryMessage> getHistory(String userId, String userMessage) {
        List<MemoryMessage> history = new ArrayList<>();
        String bootstrap = loadBootstrapContext(userId, userMessage);
        if (!bootstrap.isBlank()) {
            history.add(new MemoryMessage("system", bootstrap));
        }
        history.addAll(loadSessionMessages(userId));
        return history;
    }

    @Override
    public void append(String userId, String userMessage, String assistantReply) {
        appendSession(userId, "user", userMessage);
        appendSession(userId, "assistant", assistantReply);
        flushDailyEvent(userId, userMessage, assistantReply);
        captureDurableMemory(userId, userMessage, assistantReply);
        captureDreamSignal(userId, userMessage, assistantReply);
    }

    @Override
    public void appendUserMessage(String userId, String userMessage) {
        appendSession(userId, "user", userMessage);
        flushDailyEvent(userId, userMessage, null);
        captureDurableMemory(userId, userMessage, null);
        captureDreamSignal(userId, userMessage, null);
    }

    @Override
    public void clear(String userId) {
        sessionStore.remove(userId);
        log.debug("OpenClaw session window cleared for user={}", userId);
    }

    @Override
    public void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
        if (isBlank(userId) || imageBase64Urls == null || imageBase64Urls.isEmpty()) {
            return;
        }

        SessionSlot slot = sessionStore.computeIfAbsent(userId, key -> new SessionSlot(System.currentTimeMillis()));
        synchronized (slot) {
            slot.lastAccess = System.currentTimeMillis();
            slot.latestImageDataUrls = List.copyOf(imageBase64Urls);
            slot.latestImageSummary = summary;
        }

        appendDailySections(userId, Map.of(
                DAILY_OBSERVATIONS, List.of("Image context: " + firstNonBlank(summary, "User sent image context."))));
    }

    @Override
    public List<String> getLatestImageDataUrls(String userId) {
        SessionSlot slot = sessionStore.get(userId);
        if (slot == null || isExpired(slot) || slot.latestImageDataUrls == null || slot.latestImageDataUrls.isEmpty()) {
            return List.of();
        }
        synchronized (slot) {
            slot.lastAccess = System.currentTimeMillis();
            return List.copyOf(slot.latestImageDataUrls);
        }
    }

    @Override
    public String getLatestImageSummary(String userId) {
        SessionSlot slot = sessionStore.get(userId);
        if (slot == null || isExpired(slot)) {
            return null;
        }
        synchronized (slot) {
            slot.lastAccess = System.currentTimeMillis();
            return slot.latestImageSummary;
        }
    }

    /**
     * Runs the optional OpenClaw dreaming sweep. Candidates are written to DREAMS.md for review;
     * this method does not silently promote unreviewed observations into MEMORY.md.
     */
    public void dream(String userId) {
        if (isBlank(userId)) {
            return;
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                List<String> candidates = extractDreamCandidates(userId);
                if (candidates.isEmpty()) {
                    return;
                }
                appendDreams(userId, "Dreaming sweep", candidates);
            } catch (IOException e) {
                log.warn("failed to run OpenClaw dreaming sweep for user={}", userId, e);
            }
        }
    }

    private String loadBootstrapContext(String userId, String query) {
        if (isBlank(userId)) {
            return "";
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                cleanupExpiredDailyFiles(userId);

                String cacheKey = userId + ":bootstrap";
                long now = System.currentTimeMillis();
                FileCacheEntry cached = fileCache.get(cacheKey);
                if (cached != null && now - cached.cachedAt() < 10_000) {
                    return cached.content();
                }
                FileMtimes currentMtimes = currentFileMtimes(userId);
                if (cached != null && matchesMtimes(cached, currentMtimes)) {
                    fileCache.put(cacheKey, new FileCacheEntry(cached.content(), now, currentMtimes));
                    return cached.content();
                }

                if (vectorIndex != null && !isBlank(query)) {
                    try {
                        String retrieved = vectorIndex.retrieve(userId, query, readSourceDocuments(userId));
                        if (!retrieved.isBlank()) {
                            return truncate(retrieved, MAX_BOOTSTRAP_CHARS);
                        }
                    } catch (IOException e) {
                        log.warn("failed to retrieve OpenClaw vector memory for user={}, falling back to Markdown bootstrap",
                                userId, e);
                    }
                }
                String memory = readMemoryWithLegacyImport(userId);
                String daily = readBootstrapDailyNotes(userId);
                String dreams = readDreamsContext(userId);
                if (memory.isBlank() && daily.isBlank() && dreams.isBlank()) {
                    return "";
                }
                String context = """
                        OpenClaw memory bootstrap. Memory is persisted only in workspace Markdown files; do not infer hidden state.

                        MEMORY.md - durable curated memory:
                        %s

                        DREAMS.md - review candidates and consolidation hints:
                        %s

                        memory/*.md - retained daily notes:
                        %s
                        """.formatted(blankToPlaceholder(memory), blankToPlaceholder(dreams), blankToPlaceholder(daily));
                String result = truncate(context, MAX_BOOTSTRAP_CHARS);
                fileCache.put(cacheKey, new FileCacheEntry(result, now, currentMtimes));
                return result;
            } catch (IOException e) {
                log.warn("failed to read OpenClaw memory for user={}", userId, e);
                return "";
            }
        }
    }

    private FileMtimes currentFileMtimes(String userId) throws IOException {
        Path memFile = memoryFile(userId);
        Path dreamsFile = dreamsFile(userId);
        List<Path> dailyFiles = retainedDailyFiles(userId);
        long latestDaily = 0;
        for (Path f : dailyFiles) {
            if (Files.exists(f)) {
                latestDaily = Math.max(latestDaily, Files.getLastModifiedTime(f).toMillis());
            }
        }
        return new FileMtimes(
                Files.exists(memFile) ? Files.getLastModifiedTime(memFile).toMillis() : 0,
                Files.exists(dreamsFile) ? Files.getLastModifiedTime(dreamsFile).toMillis() : 0,
                latestDaily);
    }

    private static boolean matchesMtimes(FileCacheEntry cached, FileMtimes current) {
        FileMtimes cachedMtimes = cached.fileMtimes();
        return cachedMtimes.memory() == current.memory()
                && cachedMtimes.dreams() == current.dreams()
                && cachedMtimes.latestDaily() == current.latestDaily();
    }

    private List<VectorMemoryIndex.SourceDocument> readSourceDocuments(String userId) throws IOException {
        List<VectorMemoryIndex.SourceDocument> documents = new ArrayList<>();

        addSourceDocument(documents, "MEMORY.md", "durable", memoryFile(userId), readMemoryWithLegacyImport(userId));
        addSourceDocument(documents, "DREAMS.md", "dreams", dreamsFile(userId), readDreamsContext(userId));

        for (Path file : retainedDailyFiles(userId)) {
            addSourceDocument(documents, userDir(userId).relativize(file).toString(), "daily", file, readIfExists(file));
        }
        return documents;
    }

    private void addSourceDocument(List<VectorMemoryIndex.SourceDocument> documents, String sourcePath,
                                   String layer, Path file, String content) throws IOException {
        if (isBlank(content)) {
            return;
        }
        long mtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        documents.add(new VectorMemoryIndex.SourceDocument(sourcePath.replace('\\', '/'), layer, mtime, content));
    }

    private List<MemoryMessage> loadSessionMessages(String userId) {
        SessionSlot slot = sessionStore.get(userId);
        if (slot == null) {
            return List.of();
        }
        if (isExpired(slot)) {
            sessionStore.remove(userId);
            log.debug("OpenClaw session window expired for user={}", userId);
            return List.of();
        }
        synchronized (slot) {
            slot.lastAccess = System.currentTimeMillis();
            return new ArrayList<>(slot.messages);
        }
    }

    private void appendSession(String userId, String role, String content) {
        if (isBlank(userId) || isBlank(content) || maxMessages <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        SessionSlot slot = sessionStore.computeIfAbsent(userId, key -> new SessionSlot(now));
        synchronized (slot) {
            slot.lastAccess = now;
            slot.messages.addLast(new MemoryMessage(role, content));
            while (slot.messages.size() > maxMessages) {
                slot.messages.removeFirst();
            }
        }
    }

    private void flushDailyEvent(String userId, String userMessage, String assistantReply) {
        if (isBlank(userId) || isBlank(userMessage)) {
            return;
        }

        Map<String, List<String>> sections = new LinkedHashMap<>();
        String user = sanitizeBlock(userMessage);
        String assistant = sanitizeBlock(assistantReply);

        String customSection = extractRequestedSection(userMessage, "\u6bcf\u65e5\u8bb0\u5fc6");
        if (!isBlank(customSection)) {
            sections.put(customSection, List.of(extractRequestedMemoryContent(userMessage)));
        }

        if (looksLikeOperationalEvent(userMessage, assistantReply)) {
            sections.put(DAILY_SESSION_EVENTS, List.of(summarizePair(user, assistant)));
            sections.put(DAILY_SUMMARY, List.of(buildSummaryLine(userMessage, assistantReply)));
        }

        if (looksLikeObservation(userMessage)) {
            sections.put(DAILY_OBSERVATIONS, List.of(user));
        }

        if (looksLikeCommitment(userMessage)) {
            sections.put(DAILY_COMMITMENTS, List.of(user));
        }

        if (!sections.isEmpty()) {
            appendDailySections(userId, sections);
        }

        summarizeCompletedWeeks(userId);
    }

    private String buildSummaryLine(String userMessage, String assistantReply) {
        String topic = extractTopic(userMessage);
        String outcome = extractOutcome(assistantReply);
        if (isBlank(outcome)) {
            return topic;
        }
        return topic + " \u2192 " + outcome;
    }

    private String extractTopic(String userMessage) {
        if (isBlank(userMessage)) {
            return "";
        }
        String cleaned = userMessage
                .replaceAll("^[\\d]+[\\.,\u3001\uff0c\u3002]?\\s*", "")
                .replaceAll("^[\\u5e2e\\u8bf7\\u9ebb\\u70e6]{1,3}\\s*", "")
                .trim();
        if (cleaned.length() <= MAX_TOPIC_CHARS) {
            return cleaned;
        }
        int cut = Math.min(MAX_TOPIC_CHARS, cleaned.length());
        int lastBreak = Math.max(
                cleaned.lastIndexOf('\uff0c', cut),
                cleaned.lastIndexOf(',', cut));
        if (lastBreak > cut / 2) {
            return cleaned.substring(0, lastBreak);
        }
        return cleaned.substring(0, cut) + "...";
    }

    private String extractOutcome(String assistantReply) {
        if (isBlank(assistantReply)) {
            return "";
        }
        String cleaned = assistantReply
                .replaceAll("^#{1,6}\\s+", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replaceAll("\\|", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() <= MAX_SUMMARY_OUTCOME_CHARS) {
            return cleaned;
        }
        int cut = Math.min(MAX_SUMMARY_OUTCOME_CHARS, cleaned.length());
        int lastPeriod = Math.max(
                cleaned.lastIndexOf('\u3002', cut),
                cleaned.lastIndexOf('.', cut));
        if (lastPeriod > cut / 2) {
            return cleaned.substring(0, lastPeriod + 1);
        }
        return cleaned.substring(0, cut) + "...";
    }

    private void appendDailySections(String userId, Map<String, List<String>> sections) {
        if (isBlank(userId) || sections == null || sections.isEmpty()) {
            return;
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                cleanupExpiredDailyFiles(userId);
                Path file = dailyFile(userId, LocalDate.now(zoneId));
                ensureMarkdownFile(file, dailyHeader(LocalDate.now(zoneId)));
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (Map.Entry<String, List<String>> section : sections.entrySet()) {
                    content = insertBullets(content, sanitizeSectionTitle(section.getKey()), section.getValue());
                }
                Files.writeString(file, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                log.warn("failed to append OpenClaw daily note for user={}", userId, e);
            }
        }
    }

    private void captureDurableMemory(String userId, String userMessage, String assistantReply) {
        DurableMemory durableMemory = extractDurableMemory(userMessage, assistantReply);
        if (durableMemory == null || isBlank(durableMemory.text())) {
            return;
        }

        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                Path file = memoryFile(userId);
                ensureMarkdownFile(file, memoryHeader());
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String cleaned = sanitizeOneLine(durableMemory.text());
                if (!containsBullet(content, cleaned)) {
                    String section = firstNonBlank(
                            extractRequestedSection(userMessage, "\u957f\u671f\u8bb0\u5fc6"),
                            classifyMemorySection(cleaned, userMessage));
                    content = insertBullets(content, section, List.of(cleaned));
                    Files.writeString(file, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("failed to append OpenClaw MEMORY.md for user={}", userId, e);
            }
        }
    }

    private DurableMemory extractDurableMemory(String userMessage, String assistantReply) {
        if (isBlank(userMessage) || !containsDurableSaveIntent(userMessage)) {
            return null;
        }

        Matcher matcher = QUOTED_CONTENT.matcher(userMessage);
        String text;
        if (matcher.find()) {
            text = matcher.group(1).trim();
        } else {
            text = stripMemorySavePhrases(userMessage);
            if ((text.length() < 2 || isVagueMemoryPhrase(text)) && !isBlank(assistantReply)) {
                text = extractMemoryFromAssistantReply(assistantReply);
            }
        }

        if (isBlank(text) || isVagueMemoryPhrase(text)) {
            return null;
        }

        boolean actionSensitive = looksActionSensitive(userMessage + "\n" + text);
        if (actionSensitive) {
            text = addActionBoundary(text, userMessage);
        }
        return new DurableMemory(sanitizeOneLine(text));
    }

    private void captureDreamSignal(String userId, String userMessage, String assistantReply) {
        if (isBlank(userId) || isBlank(userMessage) || containsDurableSaveIntent(userMessage)) {
            return;
        }
        if (!looksLikeObservation(userMessage) && !looksLikeCommitment(userMessage)) {
            return;
        }

        String signal = sanitizeOneLine(summarizePair(userMessage, assistantReply));
        if (signal.length() < DREAM_MIN_SIGNAL_CHARS) {
            return;
        }

        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                appendDreams(userId, "Captured signal", List.of(signal));
            } catch (IOException e) {
                log.warn("failed to append OpenClaw DREAMS.md for user={}", userId, e);
            }
        }
    }

    private void appendDreams(String userId, String title, List<String> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Path file = dreamsFile(userId);
        ensureMarkdownFile(file, dreamsHeader());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String heading = "## " + LocalDate.now(zoneId) + " - " + title;
        if (!content.contains(heading)) {
            content = content.trim() + "\n\n" + heading + "\n";
        }
        for (String entry : entries) {
            String cleaned = sanitizeBlock(entry);
            if (!isBlank(cleaned) && !containsBullet(content, cleaned)) {
                content = insertBullets(content, LocalDate.now(zoneId) + " - " + title, List.of(cleaned));
            }
        }
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private List<String> extractDreamCandidates(String userId) throws IOException {
        Set<String> candidates = new LinkedHashSet<>();
        for (Path file : listDailyNoteFiles(userId, dailyRetentionDays)) {
            collectUsefulBullets(readIfExists(file), candidates);
        }
        return candidates.stream()
                .filter(s -> s.length() >= DREAM_MIN_SIGNAL_CHARS)
                .limit(40)
                .toList();
    }

    private String readMemoryWithLegacyImport(String userId) throws IOException {
        String memory = readIfExists(memoryFile(userId));
        String legacy = readIfExists(legacyLongTermFile(userId));
        if (memory.isBlank()) {
            return legacy;
        }
        if (legacy.isBlank() || memory.contains(legacy)) {
            return memory;
        }
        return memory + "\n\n# legacy long-term.md import\n\n" + legacy;
    }

    private String readBootstrapDailyNotes(String userId) throws IOException {
        LocalDate today = LocalDate.now(zoneId);
        List<Path> files = new ArrayList<>();

        for (int i = dailyRetentionDays - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            files.addAll(listDailyNoteFilesForDate(userId, day));
            files.addAll(listLegacyDailyFilesForDate(userId, day));
        }

        Path weeklyDir = weeklyDir(userId);
        if (Files.exists(weeklyDir)) {
            try (Stream<Path> stream = Files.list(weeklyDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.reverseOrder())
                        .limit(4)
                        .forEach(files::add);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Path file : files.stream().distinct().sorted().toList()) {
            String content = readIfExists(file);
            if (!content.isBlank()) {
                sb.append("\n\n").append(content);
            }
        }
        return sb.toString().trim();
    }

    private String readDreamsContext(String userId) throws IOException {
        return readIfExists(dreamsFile(userId));
    }

    private void cleanupExpiredDailyFiles(String userId) throws IOException {
        cleanupExpiredMarkdownFiles(openClawDailyDir(userId));
        cleanupExpiredMarkdownFiles(legacyDailyDir(userId));
        cleanupOldWeeklySummaries(userId);
    }

    private void cleanupOldWeeklySummaries(String userId) throws IOException {
        Path dir = weeklyDir(userId);
        if (!Files.exists(dir)) {
            return;
        }
        LocalDate cutoff = LocalDate.now(zoneId).minusWeeks(12);
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                String name = file.getFileName().toString();
                Matcher matcher = WEEKLY_SUMMARY_FILE.matcher(name);
                if (matcher.matches()) {
                    int year = Integer.parseInt(matcher.group(1));
                    int week = Integer.parseInt(matcher.group(2));
                    LocalDate weekMonday;
                    try {
                        weekMonday = LocalDate.now(zoneId)
                                .with(WeekFields.ISO.weekBasedYear(), year)
                                .with(WeekFields.ISO.weekOfWeekBasedYear(), week)
                                .with(DayOfWeek.MONDAY);
                    } catch (DateTimeException e) {
                        log.debug("skipping invalid weekly memory file: {}", file, e);
                        continue;
                    }
                    if (weekMonday.isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    private void cleanupExpiredMarkdownFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        LocalDate cutoff = LocalDate.now(zoneId).minusDays(dailyRetentionDays - 1L);
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".md")).toList()) {
                LocalDate day = parseDatePrefix(file.getFileName().toString());
                if (day != null && day.isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private List<Path> listDailyNoteFiles(String userId, int maxDays) throws IOException {
        LocalDate today = LocalDate.now(zoneId);
        List<Path> files = new ArrayList<>();
        for (int i = maxDays - 1; i >= 0; i--) {
            files.addAll(listDailyNoteFilesForDate(userId, today.minusDays(i)));
        }
        return files;
    }

    private List<Path> retainedDailyFiles(String userId) throws IOException {
        LocalDate today = LocalDate.now(zoneId);
        List<Path> files = new ArrayList<>();
        for (int i = dailyRetentionDays - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            files.addAll(listDailyNoteFilesForDate(userId, day));
            files.addAll(listLegacyDailyFilesForDate(userId, day));
        }
        return files.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private List<Path> listDailyNoteFilesForDate(String userId, LocalDate date) throws IOException {
        Path dir = openClawDailyDir(userId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        String prefix = DAY_FORMAT.format(date);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals(prefix + ".md")
                                || (name.startsWith(prefix + "-") && name.endsWith(".md"));
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private List<Path> listLegacyDailyFilesForDate(String userId, LocalDate date) throws IOException {
        Path dir = legacyDailyDir(userId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        String name = DAY_FORMAT.format(date) + ".md";
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals(name))
                    .toList();
        }
    }

    private String dailyHeader(LocalDate date) {
        return "# " + DAY_FORMAT.format(date) + "\n\n"
                + "OpenClaw daily note. The Daily summary section gives a concise overview; raw details live under Session events.\n\n"
                + "## " + DAILY_SUMMARY + "\n\n"
                + "## " + DAILY_SESSION_EVENTS + "\n\n"
                + "## " + DAILY_OBSERVATIONS + "\n\n"
                + "## " + DAILY_COMMITMENTS + "\n\n"
                + "## " + DAILY_CUSTOM + "\n";
    }

    // ─── Weekly summarization ──────────────────────────────────────────

    private void summarizeCompletedWeeks(String userId) {
        if (isBlank(userId)) {
            return;
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                LocalDate today = LocalDate.now(zoneId);
                for (LocalDate monday : findCompletedWeeksWithoutSummary(userId, today)) {
                    LocalDate sunday = monday.plusDays(6);
                    log.info("summarizing week {} ~ {} for user={}", monday, sunday, userId);
                    String summary = generateWeeklySummary(userId, monday, sunday);
                    if (summary.isBlank()) {
                        continue;
                    }
                    Path file = weeklySummaryFile(userId, monday);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, summary, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    deleteWeekDailyFiles(userId, monday, sunday);
                    log.info("weekly summary written and daily files cleaned for week {} ~ {}", monday, sunday);
                }
            } catch (IOException e) {
                log.warn("failed to summarize completed weeks for user={}", userId, e);
            }
        }
    }

    private List<LocalDate> findCompletedWeeksWithoutSummary(String userId, LocalDate today) throws IOException {
        List<LocalDate> result = new ArrayList<>();
        Path dailyDir = openClawDailyDir(userId);
        if (!Files.exists(dailyDir)) {
            return result;
        }

        Set<LocalDate> dailyDates = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(dailyDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                LocalDate date = parseDatePrefix(file.getFileName().toString());
                if (date != null) {
                    dailyDates.add(date);
                }
            }
        }

        if (dailyDates.isEmpty()) {
            return result;
        }

        LocalDate minDate = dailyDates.stream().min(Comparator.naturalOrder()).orElse(today);
        LocalDate monday = minDate.with(DayOfWeek.MONDAY);

        while (monday.isBefore(today)) {
            LocalDate sunday = monday.plusDays(6);
            LocalDate mon = monday;
            LocalDate sun = sunday;
            if (sun.isBefore(today)
                    && dailyDates.stream().anyMatch(d -> !d.isBefore(mon) && !d.isAfter(sun))
                    && !Files.exists(weeklySummaryFile(userId, mon))) {
                result.add(mon);
            }
            monday = monday.plusWeeks(1);
        }
        return result;
    }

    private String generateWeeklySummary(String userId, LocalDate monday, LocalDate sunday) throws IOException {
        StringBuilder overview = new StringBuilder();
        StringBuilder highlights = new StringBuilder();
        StringBuilder insights = new StringBuilder();
        Set<String> topicsSeen = new LinkedHashSet<>();
        Map<String, Integer> keywordCounts = new LinkedHashMap<>();

        for (LocalDate day = monday; !day.isAfter(sunday); day = day.plusDays(1)) {
            List<String> dailySummaries = readDailySummaryLines(userId, day);
            if (dailySummaries.isEmpty() && !dailyFileExists(userId, day)) {
                continue;
            }
            String dayName = day.getDayOfWeek().getDisplayName(
                    java.time.format.TextStyle.FULL, java.util.Locale.CHINESE);
            highlights.append("### ").append(DAY_FORMAT.format(day))
                    .append(" (").append(dayName).append(")\n");

            if (dailySummaries.isEmpty()) {
                highlights.append("*(no recorded activity)*\n");
            } else {
                for (String line : dailySummaries) {
                    String cleaned = sanitizeOneLine(line).replace("→", "→").trim();
                    if (!cleaned.isBlank() && topicsSeen.add(cleaned.substring(0, Math.min(40, cleaned.length())))) {
                        highlights.append("- ").append(cleaned).append("\n");
                    }
                }
            }
            highlights.append("\n");

            for (String line : dailySummaries) {
                countKeywords(line, keywordCounts);
            }
        }

        String topThemes = buildTopThemes(keywordCounts, 5);
        overview.append("## Overview\n\n");
        if (!topThemes.isBlank()) {
            overview.append("This week's main themes: ").append(topThemes).append(".\n\n");
        } else {
            overview.append("Light activity this week.\n\n");
        }

        overview.append("## Daily highlights\n\n").append(highlights);

        if (insights.length() > 0) {
            overview.append("## Key insights\n\n").append(insights);
        }

        String header = "# Week " + monday.get(WeekFields.ISO.weekOfWeekBasedYear())
                + " — " + DAY_FORMAT.format(monday) + " ~ " + DAY_FORMAT.format(sunday) + "\n\n";
        return truncate(header + overview, MAX_WEEKLY_SUMMARY_CHARS);
    }

    private List<String> readDailySummaryLines(String userId, LocalDate date) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path file : listDailyNoteFilesForDate(userId, date)) {
            String content = readIfExists(file);
            if (content.isBlank()) {
                continue;
            }
            boolean inSummary = false;
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## " + DAILY_SUMMARY)) {
                    inSummary = true;
                    continue;
                }
                if (inSummary && trimmed.startsWith("## ")) {
                    break;
                }
                if (inSummary && trimmed.startsWith("- ")) {
                    String bullet = trimmed.substring(2).trim();
                    int timeSep = bullet.indexOf(" - ");
                    lines.add(timeSep > 0 ? bullet.substring(timeSep + 3).trim() : bullet);
                }
            }
        }
        return lines;
    }

    private boolean dailyFileExists(String userId, LocalDate date) throws IOException {
        return !listDailyNoteFilesForDate(userId, date).isEmpty()
                || !listLegacyDailyFilesForDate(userId, date).isEmpty();
    }

    private void deleteWeekDailyFiles(String userId, LocalDate monday, LocalDate sunday) throws IOException {
        for (LocalDate day = monday; !day.isAfter(sunday); day = day.plusDays(1)) {
            for (Path file : listDailyNoteFilesForDate(userId, day)) {
                Files.deleteIfExists(file);
            }
            for (Path file : listLegacyDailyFilesForDate(userId, day)) {
                Files.deleteIfExists(file);
            }
        }
    }

    private Path weeklyDir(String userId) {
        return userDir(userId).resolve(WEEKLY_DIR);
    }

    private Path weeklySummaryFile(String userId, LocalDate monday) {
        int weekNumber = monday.get(WeekFields.ISO.weekOfWeekBasedYear());
        return weeklyDir(userId).resolve(
                monday.getYear() + "-W" + String.format("%02d", weekNumber) + ".md");
    }

    private void countKeywords(String text, Map<String, Integer> counts) {
        if (isBlank(text)) {
            return;
        }
        String[] words = {"旅行", "出行", "规划", "天气", "搜索", "设计", "海报",
                "查询", "推荐", "餐厅", "美食", "住宿", "高铁", "火车", "门票",
                "文章", "写作", "B站", "视频", "语音", "提醒", "任务", "偏好",
                "身份", "口味", "总结", "分析", "编程", "代码", "修复", "优化",
                "学习", "课程", "考试", "社团", "招新", "游戏", "娱乐"};
        for (String word : words) {
            if (text.contains(word)) {
                counts.merge(word, 1, Integer::sum);
            }
        }
    }

    private String buildTopThemes(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) {
            return "";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + "(" + e.getValue() + "次)")
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }

    private String memoryHeader() {
        return "# MEMORY.md\n\n"
                + "Durable, compact memory loaded at session start. Keep raw logs in memory/YYYY-MM-DD.md.\n\n"
                + "## " + SEC_PREFERENCES + "\n\n"
                + "## " + SEC_PROJECTS + "\n\n"
                + "## " + SEC_DECISIONS + "\n\n"
                + "## " + SEC_ACTION_BOUNDARIES + "\n\n"
                + "## " + SEC_FACTS + "\n";
    }

    private String dreamsHeader() {
        return "# DREAMS.md\n\n"
                + "Review surface for dreaming and consolidation candidates. Entries here are not automatically trusted durable memory.\n";
    }

    private String insertBullets(String content, String sectionTitle, List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return content;
        }

        StringBuilder block = new StringBuilder();
        String time = TIME_FORMAT.format(LocalDateTime.now(zoneId));
        for (String bullet : bullets) {
            String sanitized = sanitizeBlock(bullet);
            if (!isBlank(sanitized) && !containsBullet(content, sanitized)) {
                block.append("- ").append(time).append(" - ").append(sanitized).append("\n");
            }
        }
        return insertAfterHeading(content, "## " + sectionTitle, block.toString());
    }

    private String insertAfterHeading(String content, String heading, String block) {
        if (isBlank(block)) {
            return content;
        }
        int headingIndex = content.indexOf(heading);
        if (headingIndex < 0) {
            return content.trim() + "\n\n" + heading + "\n" + block;
        }
        int insertAt = content.indexOf('\n', headingIndex);
        if (insertAt < 0) {
            return content + "\n" + block;
        }
        insertAt++;
        while (insertAt < content.length() && content.charAt(insertAt) == '\n') {
            insertAt++;
        }
        return content.substring(0, insertAt) + block + content.substring(insertAt);
    }

    private void collectUsefulBullets(String content, Set<String> bullets) {
        if (isBlank(content)) {
            return;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                String withoutBullet = trimmed.substring(2).trim();
                int timeSep = withoutBullet.indexOf(" - ");
                bullets.add(timeSep > 0 ? withoutBullet.substring(timeSep + 3).trim() : withoutBullet);
            }
        }
    }

    private String addActionBoundary(String memoryText, String source) {
        String boundary = "Source: user message. Future behavior must respect any approval, expiry, owner, or safe-to-act condition stated here.";
        if (containsAny(source, "\u4e34\u65f6", "\u4eca\u5929", "\u660e\u5929", "\u5230\u671f", "temporary", "until", "expires")) {
            boundary += " This appears temporary; do not treat it as permanent without confirmation.";
        }
        return memoryText + " (" + boundary + ")";
    }

    private boolean containsDurableSaveIntent(String text) {
        return containsAny(text, "\u957f\u671f\u8bb0\u5fc6", "\u8bb0\u4f4f", "remember", "memory")
                && containsAny(text,
                "\u5b58\u5165", "\u4fdd\u5b58", "\u52a0\u5165", "\u8bb0\u5230", "\u8bb0\u5165",
                "\u8bb0\u4f4f", "save", "store", "remember");
    }

    private String stripMemorySavePhrases(String text) {
        return MEMORY_SAVE_PHRASE.matcher(text).replaceAll("").trim();
    }

    private String extractMemoryFromAssistantReply(String assistantReply) {
        if (isBlank(assistantReply)) {
            return null;
        }
        String clean = assistantReply
                .replaceAll("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", " ")
                .replaceAll("[\\u2600-\\u27BF\\uFE0F\\u200D\\u200B]", " ");

        Matcher matcher = ASSISTANT_MEMORY_PATTERN_1.matcher(clean);
        if (matcher.find() && matcher.group(1).trim().length() >= 4) {
            return matcher.group(1).trim();
        }

        matcher = ASSISTANT_MEMORY_PATTERN_2.matcher(clean);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String classifyMemorySection(String memoryText, String sourceText) {
        String all = memoryText + "\n" + sourceText;
        if (looksActionSensitive(all)) {
            return SEC_ACTION_BOUNDARIES;
        }
        if (containsAny(all, "\u559c\u6b22", "\u4e0d\u559c\u6b22", "\u504f\u597d", "\u4e60\u60ef",
                "prefer", "preference", "style")) {
            return SEC_PREFERENCES;
        }
        if (containsAny(all, "\u9879\u76ee", "\u5e73\u53f0", "\u4ee3\u7801", "project", "repo", "platform")) {
            return SEC_PROJECTS;
        }
        if (containsAny(all, "\u51b3\u5b9a", "\u9009\u62e9", "\u65b9\u6848", "decision", "decided")) {
            return SEC_DECISIONS;
        }
        return SEC_FACTS;
    }

    private boolean looksActionSensitive(String text) {
        return containsAny(text,
                "\u6279\u51c6", "\u5141\u8bb8", "\u786e\u8ba4", "\u5220\u9664", "\u5230\u671f",
                "\u4e34\u65f6", "\u4e0d\u8981", "\u7981\u6b62", "\u79fb\u4ea4", "\u7b49\u5230",
                "approval", "permission", "confirm", "delete", "expires", "temporary", "until",
                "do not", "handoff", "safe to act");
    }

    private boolean looksLikeOperationalEvent(String userMessage, String assistantReply) {
        return containsAny(userMessage,
                "\u5e2e\u6211", "\u8bf7", "\u4f18\u5316", "\u4fee\u590d", "\u5220\u9664", "\u521b\u5efa",
                "\u5efa\u7acb", "\u751f\u6210", "\u5199", "\u67e5\u8be2", "\u5206\u6790", "\u603b\u7ed3",
                "fix", "create", "delete", "write", "summarize", "analyze")
                || containsAny(assistantReply,
                "\u5df2", "\u5b8c\u6210", "\u5df2\u5b8c\u6210", "\u5904\u7406\u5b8c",
                "done", "completed", "fixed", "created");
    }

    private boolean looksLikeObservation(String text) {
        return containsAny(text,
                "\u6211\u559c\u6b22", "\u6211\u4e0d\u559c\u6b22", "\u6211\u662f", "\u6211\u7684",
                "\u504f\u597d", "\u4e60\u60ef", "\u91cd\u8981", "\u4e0a\u4e0b\u6587",
                "prefer", "preference", "remember", "important");
    }

    private boolean looksLikeCommitment(String text) {
        return containsAny(text,
                "\u5f85\u529e", "\u63d0\u9192", "\u4e34\u65f6", "\u7a0d\u540e", "\u4e00\u4f1a",
                "\u660e\u5929", "\u540e\u5929", "\u4eca\u5929", "\u8ba1\u5212", "\u4efb\u52a1",
                "todo", "remind", "temporary", "later", "tomorrow");
    }

    private String extractRequestedSection(String text, String memoryKeyword) {
        if (isBlank(text) || isBlank(memoryKeyword) || !text.contains(memoryKeyword)) {
            return null;
        }

        List<String> patterns = List.of(
                memoryKeyword + "\u7684([^:\uff1a\\s]+)",
                memoryKeyword + "\u5206\u7c7b\u4e3a([^:\uff1a\\s]+)",
                "\u5b58\u5230" + memoryKeyword + "\u7684([^:\uff1a\\s]+)",
                "\u8bb0\u5230" + memoryKeyword + "\u7684([^:\uff1a\\s]+)",
                "\u8bb0\u5f55\u5230" + memoryKeyword + "\u7684([^:\uff1a\\s]+)"
        );

        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.find()) {
                return sanitizeSectionTitle(matcher.group(1));
            }
        }

        if ("\u6bcf\u65e5\u8bb0\u5fc6".equals(memoryKeyword)
                && containsAny(text, "\u8bb0\u5f55", "\u8bb0\u5230", "\u5199\u5165", "\u5b58\u5165", "\u4fdd\u5b58")) {
            return DAILY_CUSTOM;
        }
        return null;
    }

    private String extractRequestedMemoryContent(String text) {
        if (isBlank(text)) {
            return "";
        }
        Matcher matcher = QUOTED_CONTENT.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int split = firstSplitIndex(text);
        if (split >= 0 && split + 1 < text.length()) {
            return sanitizeOneLine(text.substring(split + 1));
        }
        return sanitizeOneLine(text);
    }

    private String summarizePair(String user, String assistant) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(user)) {
            sb.append("request=").append(sanitizeOneLine(user));
        }
        if (!isBlank(assistant)) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append("result=").append(extractResultSnippet(assistant));
        }
        return sb.toString();
    }

    private String extractResultSnippet(String assistant) {
        if (isBlank(assistant)) {
            return "";
        }
        String cleaned = sanitizeOneLine(assistant);
        if (cleaned.length() <= MAX_RESULT_CHARS) {
            return cleaned;
        }
        int cut = Math.min(MAX_RESULT_CHARS, cleaned.length());
        int lastPeriod = cleaned.lastIndexOf('。', cut);
        int lastDot = cleaned.lastIndexOf('.', cut);
        int breakPoint = Math.max(lastPeriod, lastDot);
        if (breakPoint > cut / 2) {
            return cleaned.substring(0, breakPoint + 1) + "...";
        }
        return cleaned.substring(0, cut) + "...";
    }

    private int firstSplitIndex(String text) {
        if (isBlank(text)) {
            return -1;
        }
        int split = text.indexOf(':');
        if (split < 0) {
            split = text.indexOf('\uff1a');
        }
        return split;
    }

    private Path userDir(String userId) {
        return basePath.resolve(safeFileName(userId));
    }

    private Path openClawDailyDir(String userId) {
        return userDir(userId).resolve("memory");
    }

    private Path legacyDailyDir(String userId) {
        return userDir(userId).resolve("daily");
    }

    private Path dailyFile(String userId, LocalDate date) throws IOException {
        Path dir = openClawDailyDir(userId);
        Files.createDirectories(dir);
        return dir.resolve(DAY_FORMAT.format(date) + ".md");
    }

    private Path memoryFile(String userId) throws IOException {
        Path file = userDir(userId).resolve("MEMORY.md");
        Files.createDirectories(file.getParent());
        return file;
    }

    private Path legacyLongTermFile(String userId) throws IOException {
        Path file = userDir(userId).resolve("long-term.md");
        Files.createDirectories(file.getParent());
        return file;
    }

    private Path dreamsFile(String userId) throws IOException {
        Path file = userDir(userId).resolve("DREAMS.md");
        Files.createDirectories(file.getParent());
        return file;
    }

    private void ensureMarkdownFile(Path file, String header) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, header, StandardCharsets.UTF_8);
        }
    }

    private String readIfExists(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    private Object userFileLock(String userId) {
        return userFileLocks.computeIfAbsent(safeFileName(userId), key -> new Object());
    }

    private boolean isExpired(SessionSlot slot) {
        return ttlMillis > 0 && System.currentTimeMillis() - slot.lastAccess > ttlMillis;
    }

    private boolean containsBullet(String content, String value) {
        if (isBlank(content) || isBlank(value)) {
            return false;
        }
        String normalized = sanitizeOneLine(value);
        if (normalized.isEmpty()) {
            return false;
        }
        return content.lines().anyMatch(line -> {
            if (!line.trim().startsWith("- ")) {
                return false;
            }
            return sanitizeOneLine(line).contains(normalized);
        });
    }

    private boolean isVagueMemoryPhrase(String cleaned) {
        if (isBlank(cleaned) || cleaned.length() <= 2) {
            return true;
        }
        return cleaned.matches("[\u6211\u4f60\u4ed6\u5979]\u7684[\u4e00-\u9fff]{1,4}") && cleaned.length() < 6;
    }

    private static String safeFileName(String value) {
        if (isBlank(value)) {
            return "unknown";
        }
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static String sanitizeSectionTitle(String value) {
        if (isBlank(value)) {
            return DAILY_CUSTOM;
        }
        return sanitizeOneLine(value)
                .replace("#", "")
                .replace("|", "")
                .trim();
    }

    private static LocalDate parseDatePrefix(String fileName) {
        try {
            String date = fileName.length() >= 10 ? fileName.substring(0, 10) : fileName.replace(".md", "");
            return LocalDate.parse(date, DAY_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeOneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String sanitizeBlock(String text) {
        String oneLine = sanitizeOneLine(text);
        return oneLine.length() > MAX_DAILY_NOTE_CHARS
                ? oneLine.substring(0, MAX_DAILY_NOTE_CHARS) + "..."
                : oneLine;
    }

    private static String truncate(String text, int maxLength) {
        String clean = text == null ? "" : text;
        return clean.length() > maxLength ? clean.substring(0, maxLength) + "..." : clean;
    }

    private static String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first;
    }

    private static String blankToPlaceholder(String text) {
        return isBlank(text) ? "(none)" : text;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DurableMemory(String text) {
    }

    private record FileCacheEntry(String content, long cachedAt, FileMtimes fileMtimes) {
    }

    private record FileMtimes(long memory, long dreams, long latestDaily) {
    }

    private static class SessionSlot {
        final Deque<MemoryMessage> messages = new ArrayDeque<>();
        volatile List<String> latestImageDataUrls;
        volatile String latestImageSummary;
        volatile long lastAccess;

        SessionSlot(long lastAccess) {
            this.lastAccess = lastAccess;
        }
    }
}
