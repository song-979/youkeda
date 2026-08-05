package com.youkeda.project.wechatproject.bot.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import com.youkeda.project.wechatproject.bot.tool.JsonExtractUtil;
import com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil;
import org.springframework.beans.factory.DisposableBean;
import com.youkeda.project.wechatproject.bot.tool.TextDecodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenClaw-style file memory implementing {@link ConversationMemory}.
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
public class OpenClawConversationMemory implements ConversationMemory, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OpenClawConversationMemory.class);

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern QUOTED_CONTENT =
            Pattern.compile("[\"'\\u201c\\u201d\\u2018\\u2019](.+?)[\"'\\u201c\\u201d\\u2018\\u2019]");

    private static final String MEMORY_SYSTEM_PROMPT =
            "You are a precise JSON output engine. Your only function is to analyze conversation turns "
                    + "and output structured JSON for memory storage. Never add explanations, markdown, or conversation. "
                    + "Output ONLY the JSON object, nothing else.";

    private static final String SEC_PREFERENCES = "Preferences";
    private static final String SEC_PROJECTS = "Projects";
    private static final String SEC_DECISIONS = "Decisions";
    private static final String SEC_ACTION_BOUNDARIES = "Action-sensitive boundaries";
    private static final String SEC_FACTS = "Facts";

    private static final String DAILY_SESSION_EVENTS = "Session events";
    private static final String DAILY_OBSERVATIONS = "Observations";
    private static final String DAILY_COMMITMENTS = "Commitments";
    private static final String DAILY_CUSTOM = "Custom notes";

    private static final int MAX_BOOTSTRAP_TOKENS = 2_000;
    private static final int MAX_DAILY_NOTE_CHARS = 1_200;
    private static final int DREAM_MIN_SIGNAL_CHARS = 8;
    private static final int DREAM_CONSOLIDATION_THRESHOLD = 5;

    private final int maxMessages;
    private final long ttlMillis;
    private final Path basePath;
    private final int dailyRetentionDays;
    private final ZoneId zoneId;
    private final VectorMemoryIndex vectorIndex;
    private final AiModelClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, SessionSlot> sessionStore = new ConcurrentHashMap<>(64);
    private final Map<String, Object> userFileLocks = new ConcurrentHashMap<>(64);
    private final Map<String, FileCacheEntry> fileCache = new ConcurrentHashMap<>(64);
    private final Map<String, AtomicInteger> summaryCounters = new ConcurrentHashMap<>(64);
    private final ScheduledExecutorService cacheCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "openclaw-cache-cleaner");
        t.setDaemon(true);
        return t;
    });
    private final ThreadPoolExecutor persistenceExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
                Thread t = new Thread(r, "openclaw-memory-persist");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.CallerRunsPolicy());

    {
        cacheCleaner.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            fileCache.entrySet().removeIf(e -> now - e.getValue().cachedAt > 120_000);
            sessionStore.entrySet().removeIf(entry -> {
                boolean expired = isExpired(entry.getValue());
                if (expired) {
                    String key = safeFileName(entry.getKey());
                    userFileLocks.remove(key);
                    summaryCounters.remove(key);
                }
                return expired;
            });
        }, 60, 60, TimeUnit.SECONDS);
    }

    public OpenClawConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays) {
        this(maxHistoryRounds, memoryTtlMinutes, basePath, dailyRetentionDays, null, null);
    }

    public OpenClawConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays,
                                      VectorMemoryIndex vectorIndex) {
        this(maxHistoryRounds, memoryTtlMinutes, basePath, dailyRetentionDays, vectorIndex, null);
    }

    public OpenClawConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays,
                                      VectorMemoryIndex vectorIndex,
                                      AiModelClient aiClient) {
        this.maxMessages = Math.max(0, maxHistoryRounds) * 2;
        this.ttlMillis = Math.max(0L, memoryTtlMinutes) * 60_000L;
        this.basePath = Path.of(isBlank(basePath) ? "data/memory" : basePath).toAbsolutePath().normalize();
        this.dailyRetentionDays = Math.max(2, dailyRetentionDays);
        this.zoneId = ZoneId.systemDefault();
        this.vectorIndex = vectorIndex;
        this.aiClient = aiClient;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create OpenClaw memory directory: " + this.basePath, e);
        }
    }

    // ── ConversationMemory implementation ──────────────────────

    @Override
    public List<ChatRequest.Message> getHistory(String userId) {
        return getHistory(userId, null);
    }

    @Override
    public List<ChatRequest.Message> getHistory(String userId, String userMessage) {
        List<ChatRequest.Message> history = new ArrayList<>();
        String bootstrap = loadBootstrapContext(userId, userMessage);
        if (!bootstrap.isBlank()) {
            history.add(new ChatRequest.Message("system", bootstrap));
        }
        history.addAll(loadSessionMessages(userId));
        return history;
    }

    @Override
    public void append(String userId, String userMessage, String assistantReply) {
        appendSession(userId, "user", userMessage);
        appendSession(userId, "assistant", assistantReply);
        persistTurnAsync(userId, userMessage, assistantReply);
    }

    @Override
    public void appendUserMessage(String userId, String userMessage) {
        appendSession(userId, "user", userMessage);
        persistTurnAsync(userId, userMessage, null);
    }

    @Override
    public void clear(String userId) {
        sessionStore.remove(userId);
        fileCache.remove(userId + ":bootstrap");
        userFileLocks.remove(safeFileName(userId));
        summaryCounters.remove(safeFileName(userId));
        log.debug("OpenClaw session window cleared for user={}", userId);
    }

    @Override
    public void destroy() {
        cacheCleaner.shutdownNow();
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }
    }

    private void persistTurnAsync(String userId, String userMessage, String assistantReply) {
        Runnable task = () -> {
            if (aiClient != null && shouldSummarizeWithLlm(userId)) {
                llmSummarizeAndPersist(userId, userMessage, assistantReply);
            } else {
                flushDailyEvent(userId, userMessage, assistantReply);
                captureDurableMemory(userId, userMessage, assistantReply);
                captureDreamSignal(userId, userMessage, assistantReply);
            }
        };
        try {
            persistenceExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("memory persistence queue rejected user={}, running inline", userId);
            task.run();
        }
    }

    private boolean shouldSummarizeWithLlm(String userId) {
        int round = summaryCounters.computeIfAbsent(safeFileName(userId), key -> new AtomicInteger())
                .incrementAndGet();
        return round == 1 || round % 3 == 0;
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

    // ── dreaming (public for manual/scheduled invocation) ──────

    /**
     * Runs the OpenClaw dreaming sweep. When an AI client is available, accumulated dream
     * signals are reviewed by the LLM and confident entries are automatically promoted to
     * MEMORY.md. Without an AI client, candidates are re-organized in DREAMS.md for manual review.
     */
    public void dream(String userId) {
        if (isBlank(userId)) {
            return;
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                if (aiClient != null) {
                    consolidateDreamsWithLLM(userId);
                } else {
                    List<String> candidates = extractDreamCandidates(userId);
                    if (candidates.isEmpty()) {
                        return;
                    }
                    appendDreams(userId, "Dreaming sweep", candidates);
                }
            } catch (IOException e) {
                log.warn("failed to run OpenClaw dreaming sweep for user={}", userId, e);
            }
        }
    }

    /**
     * Uses the LLM to review accumulated dream signals and promote durable ones to MEMORY.md.
     * Remaining (non-promoted) signals stay in DREAMS.md for future review.
     */
    private void consolidateDreamsWithLLM(String userId) throws IOException {
        String dreamsContent = readDreamsContext(userId);
        if (dreamsContent.isBlank()) {
            return;
        }

        Set<String> allSignals = new LinkedHashSet<>();
        collectUsefulBullets(dreamsContent, allSignals);
        if (allSignals.isEmpty()) {
            return;
        }

        String signalsList = allSignals.stream()
                .map(s -> "- " + s)
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = buildConsolidationPrompt(signalsList);
        String llmResponse;
        try {
            llmResponse = aiClient.chat(prompt, List.of(), List.of(), MEMORY_SYSTEM_PROMPT);
        } catch (Exception e) {
            log.warn("LLM dream consolidation call failed for user={}, falling back", userId, e);
            return;
        }

        List<DurableEntry> promoted = parseConsolidationResponse(llmResponse);
        if (promoted.isEmpty()) {
            log.debug("No dream signals promoted for user={}", userId);
            return;
        }

        // Write promoted entries to MEMORY.md
        Path memFile = memoryFile(userId);
        ensureMarkdownFile(memFile, memoryHeader());
        String memContent = Files.readString(memFile, StandardCharsets.UTF_8);
        int promotedCount = 0;
        for (DurableEntry entry : promoted) {
            String cleaned = sanitizeOneLine(entry.content());
            if (!isBlank(cleaned) && !containsBullet(memContent, cleaned)) {
                memContent = insertBullets(memContent, entry.section(), List.of(cleaned));
                promotedCount++;
            }
        }
        if (promotedCount > 0) {
            Files.writeString(memFile, memContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        // Remove promoted entries from DREAMS.md
        String remaining = removePromotedFromDreams(dreamsContent, promoted);
        Path dreamsFile = dreamsFile(userId);
        if (remaining.isBlank() || remaining.trim().equals(dreamsHeader().trim())) {
            // All dreams were promoted or file is effectively empty — clear it
            Files.writeString(dreamsFile, dreamsHeader(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            Files.writeString(dreamsFile, remaining, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        log.info("Dream consolidation: promoted {} entries to MEMORY.md for user={}", promotedCount, userId);
    }

    private String buildConsolidationPrompt(String signalsList) {
        return """
                Review these accumulated dream signals from previous conversations.
                Some may represent durable user preferences, facts, or decisions worth remembering permanently.
                Others are transient observations that should stay in the dream buffer.

                Dream signals:
                %s

                Return ONLY a JSON object (no markdown, no explanation) with this format:

                {
                  "promotedMemories": [
                    {"section": "Preferences|Projects|Decisions|Action-sensitive boundaries|Facts", "content": "concise one-line fact in Chinese"}
                  ]
                }

                Rules:
                - Only promote signals that represent truly durable information: user preferences, identity, recurring needs, important personal facts, or decisions.
                - Do NOT promote one-off requests, transient observations, or conversational trivia.
                - If multiple signals convey the same information, consolidate into a single entry.
                - If two signals conflict, pick the more recent or more specific one.
                - If no signals are worth promoting, return an empty array.
                - Classify each promoted entry into the most appropriate section.
                - Content must be in Chinese (the same language as the signals).
                """.formatted(signalsList);
    }

    @SuppressWarnings("unchecked")
    private List<DurableEntry> parseConsolidationResponse(String llmResponse) {
        if (isBlank(llmResponse)) {
            return List.of();
        }
        String json = extractJsonBlock(llmResponse);
        if (json == null) {
            log.debug("no JSON block found in consolidation response");
            return List.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object promoted = raw.get("promotedMemories");
            if (!(promoted instanceof List<?> list)) {
                return List.of();
            }
            List<DurableEntry> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String section = String.valueOf(m.getOrDefault("section", "Facts"));
                    String content = String.valueOf(m.getOrDefault("content", ""));
                    if (!content.isBlank() && !"null".equals(content)) {
                        result.add(new DurableEntry(sanitizeSectionTitle(section), content));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("failed to parse consolidation response JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Removes promoted entries from the DREAMS.md content by matching against bullet text.
     */
    private String removePromotedFromDreams(String dreamsContent, List<DurableEntry> promoted) {
        Set<String> promotedTexts = new LinkedHashSet<>();
        for (DurableEntry entry : promoted) {
            String cleaned = sanitizeOneLine(entry.content());
            if (!cleaned.isBlank()) {
                promotedTexts.add(cleaned);
            }
        }
        if (promotedTexts.isEmpty()) {
            return dreamsContent;
        }

        StringBuilder result = new StringBuilder();
        for (String line : dreamsContent.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                String withoutBullet = trimmed.substring(2).trim();
                int timeSep = withoutBullet.indexOf(" - ");
                String signalText = timeSep > 0 ? withoutBullet.substring(timeSep + 3).trim() : withoutBullet;
                boolean shouldRemove = false;
                for (String promotedText : promotedTexts) {
                    if (signalText.contains(promotedText) || promotedText.contains(signalText)
                            || sanitizeOneLine(signalText).equals(sanitizeOneLine(promotedText))) {
                        shouldRemove = true;
                        break;
                    }
                }
                if (shouldRemove) {
                    continue; // skip this line
                }
            }
            result.append(line).append("\n");
        }
        return result.toString().trim();
    }

    private int countDreamBullets(String dreamsContent) {
        if (isBlank(dreamsContent)) {
            return 0;
        }
        int count = 0;
        for (String line : dreamsContent.split("\\R")) {
            if (line.trim().startsWith("- ")) {
                count++;
            }
        }
        return count;
    }

    // ── bootstrap ──────────────────────────────────────────────

    private String loadBootstrapContext(String userId, String query) {
        if (isBlank(userId)) {
            return "";
        }
        Object lock = userFileLock(userId);
        synchronized (lock) {
            try {
                cleanupExpiredDailyFiles(userId);
                if (vectorIndex != null && !isBlank(query)) {
                    try {
                        String retrieved = vectorIndex.retrieve(userId, query, readSourceDocuments(userId));
                        if (!retrieved.isBlank()) {
                            return TokenBudgetUtil.truncateAtBoundary(retrieved, MAX_BOOTSTRAP_TOKENS);
                        }
                    } catch (IOException e) {
                        log.warn("failed to retrieve OpenClaw vector memory for user={}, falling back to Markdown bootstrap",
                                userId, e);
                    }
                }
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

                        DREAMS.md - UNREVIEWED candidates only; never treat these as established facts:
                        %s

                        memory/*.md - retained daily notes:
                        %s
                        """.formatted(blankToPlaceholder(memory), blankToPlaceholder(dreams), blankToPlaceholder(daily));
                String result = TokenBudgetUtil.truncateAtBoundary(context, MAX_BOOTSTRAP_TOKENS);
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

    // ── session window ─────────────────────────────────────────

    private List<ChatRequest.Message> loadSessionMessages(String userId) {
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
            ChatRequest.Message last = slot.messages.peekLast();
            if (last != null && role.equals(last.getRole())) {
                slot.messages.removeLast();
                slot.messages.addLast(new ChatRequest.Message(role,
                        String.valueOf(last.getContent()) + "\n" + content));
            } else {
                slot.messages.addLast(new ChatRequest.Message(role, content));
            }
            while (slot.messages.size() > maxMessages) {
                slot.messages.removeFirst();
            }
        }
    }

    // ── LLM-driven memory summarization ────────────────────────

    private void llmSummarizeAndPersist(String userId, String userMessage, String assistantReply) {
        try {
            String llmResponse = aiClient.chat(buildSummaryPrompt(userMessage, assistantReply),
                    List.of(), List.of(), MEMORY_SYSTEM_PROMPT);
            MemorySummary summary = parseMemorySummary(llmResponse);
            if (summary == null) {
                log.debug("LLM summary parse returned null for user={}, falling back to keyword-based", userId);
                flushDailyEvent(userId, userMessage, assistantReply);
                captureDurableMemory(userId, userMessage, assistantReply);
                captureDreamSignal(userId, userMessage, assistantReply);
                return;
            }
            if (summary.dailyEvents() != null && !summary.dailyEvents().isEmpty()) {
                Map<String, List<String>> sections = new LinkedHashMap<>();
                for (DailyEvent ev : summary.dailyEvents()) {
                    sections.computeIfAbsent(ev.section(), k -> new ArrayList<>()).add(ev.content());
                }
                appendDailySections(userId, sections);
            }
            for (DurableEntry dm : summary.durableMemories()) {
                Object lock = userFileLock(userId);
                synchronized (lock) {
                    Path file = memoryFile(userId);
                    ensureMarkdownFile(file, memoryHeader());
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String cleaned = sanitizeOneLine(dm.content());
                    if (!isBlank(cleaned) && !containsBullet(content, cleaned)) {
                        content = insertBullets(content, dm.section(), List.of(cleaned));
                        Files.writeString(file, content, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                }
            }
            if (summary.dreamSignals() != null && !summary.dreamSignals().isEmpty()) {
                List<String> signals = summary.dreamSignals().stream()
                        .map(s -> sanitizeOneLine(s.content()))
                        .filter(s -> !isBlank(s) && s.length() >= DREAM_MIN_SIGNAL_CHARS)
                        .toList();
                if (!signals.isEmpty()) {
                    appendDreams(userId, "Captured signal", signals);
                }
            }
        } catch (Exception e) {
            log.warn("LLM memory summarization failed for user={}, falling back to keyword-based", userId, e);
            flushDailyEvent(userId, userMessage, assistantReply);
            captureDurableMemory(userId, userMessage, assistantReply);
            captureDreamSignal(userId, userMessage, assistantReply);
        }
    }

    private String buildSummaryPrompt(String userMessage, String assistantReply) {
        String user = userMessage != null ? userMessage : "(empty)";
        String assistant = assistantReply != null ? assistantReply : "(empty)";
        return """
                Analyze this conversation turn and produce a memory summary in JSON format.

                USER MESSAGE: %s

                ASSISTANT REPLY: %s

                Return ONLY a JSON object (no markdown, no explanation) with these fields:

                {
                  "dailyEvents": [
                    {"section": "Session events", "content": "detailed summary of what was requested and what the result was. Keep specifics: names, numbers, URLs, dates. Do NOT abbreviate or generalize."},
                    {"section": "Observations", "content": "what was learned about the user in this turn. Only include if the user revealed preferences, identity, context, or habits."},
                    {"section": "Commitments", "content": "tasks, reminders, or promises that need follow-up. Only include if there is an actionable commitment."}
                  ],
                  "durableMemories": [
                    {"section": "Preferences|Projects|Decisions|Action-sensitive boundaries|Facts", "content": "concise one-line fact worth remembering permanently. Only include truly durable, reusable facts. Do NOT include one-off requests or transient info."}
                  ],
                  "dreamSignals": [
                    {"section": "Observations|Commitments", "content": "borderline signal that MIGHT be important but needs later review. Less confident than durableMemories. Can be more speculative."}
                  ]
                }

                Rules:
                - dailyEvents: include ALL three sections (Session events is mandatory). Preserve detail—write 2-4 sentences per event. Don't summarize away specifics.
                - durableMemories: be conservative. Only include facts that will still be relevant days/weeks later. If unsure, put it in dreamSignals instead.
                - dreamSignals: for interesting but uncertain observations or soft commitments.
                - If a section has nothing to report, omit it from the array entirely.
                - All content must be in Chinese (the same language as the conversation).
                """.formatted(truncate(user, 3000), truncate(assistant, 4000));
    }

    private MemorySummary parseMemorySummary(String llmResponse) {
        if (isBlank(llmResponse)) {
            return null;
        }
        String json = extractJsonBlock(llmResponse);
        if (json == null) {
            log.debug("no JSON block found in LLM memory summary response");
            return null;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            List<DailyEvent> dailyEvents = parseEventList(raw.get("dailyEvents"));
            List<DurableEntry> durable = parseDurableList(raw.get("durableMemories"));
            List<DreamEntry> dreams = parseDreamList(raw.get("dreamSignals"));
            if (dailyEvents.isEmpty() && durable.isEmpty() && dreams.isEmpty()) {
                log.debug("LLM memory summary parsed but all lists empty");
                return null;
            }
            return new MemorySummary(dailyEvents, durable, dreams);
        } catch (Exception e) {
            log.debug("failed to parse LLM memory summary JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a JSON object from an LLM response that may contain surrounding text,
     * markdown code fences, [FILE:] tags, or other non-JSON content.
     */
    private String extractJsonBlock(String text) {
        String stripped = text.replaceAll("(?s)\\[FILE:[^]]*?\\].*?\\[/FILE\\]", "").trim();
        return JsonExtractUtil.extractJsonObject(stripped);
    }

    @SuppressWarnings("unchecked")
    private List<DailyEvent> parseEventList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<DailyEvent> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                String section = String.valueOf(m.getOrDefault("section", ""));
                String content = String.valueOf(m.getOrDefault("content", ""));
                if (!section.isBlank() && !content.isBlank() && !"null".equals(content)) {
                    result.add(new DailyEvent(sanitizeSectionTitle(section), sanitizeBlock(content)));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<DurableEntry> parseDurableList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<DurableEntry> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                String section = String.valueOf(m.getOrDefault("section", "Facts"));
                String content = String.valueOf(m.getOrDefault("content", ""));
                if (!content.isBlank() && !"null".equals(content)) {
                    result.add(new DurableEntry(sanitizeSectionTitle(section), content));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<DreamEntry> parseDreamList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<DreamEntry> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                String section = String.valueOf(m.getOrDefault("section", "Observations"));
                String content = String.valueOf(m.getOrDefault("content", ""));
                if (!content.isBlank() && !"null".equals(content)) {
                    result.add(new DreamEntry(section, content));
                }
            }
        }
        return result;
    }

    private record MemorySummary(List<DailyEvent> dailyEvents, List<DurableEntry> durableMemories,
                                  List<DreamEntry> dreamSignals) {}
    private record DailyEvent(String section, String content) {}
    private record DurableEntry(String section, String content) {}
    private record DreamEntry(String section, String content) {}

    // ── daily persistence ──────────────────────────────────────

    private void flushDailyEvent(String userId, String userMessage, String assistantReply) {
        if (isBlank(userId) || isBlank(userMessage)) {
            return;
        }
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String user = sanitizeBlock(userMessage);
        String assistant = sanitizeBlock(assistantReply);

        String customSection = extractRequestedSection(userMessage, "每日记忆");
        if (!isBlank(customSection)) {
            sections.put(customSection, List.of(extractRequestedMemoryContent(userMessage)));
        }
        if (looksLikeOperationalEvent(userMessage, assistantReply)) {
            sections.put(DAILY_SESSION_EVENTS, List.of(summarizePair(user, assistant)));
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

    // ── durable memory (MEMORY.md) ─────────────────────────────

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
                            extractRequestedSection(userMessage, "长期记忆"),
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

    private String extractMemoryFromAssistantReply(String assistantReply) {
        if (isBlank(assistantReply)) {
            return null;
        }
        String clean = assistantReply
                .replaceAll("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", " ")
                .replaceAll("[\\u2600-\\u27BF\\uFE0F\\u200D\\u200B]", " ");
        Matcher matcher = Pattern.compile("你的[^：:]{0,30}[：:]\\s*(.+?)(?:以后|下次|自动|～|。|！|$)")
                .matcher(clean);
        if (matcher.find() && matcher.group(1).trim().length() >= 4) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("[：:]\\s*([^：:。！\\n]{4,80})").matcher(clean);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    // ── dreams (DREAMS.md) ─────────────────────────────────────

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

                // Auto-trigger consolidation when enough signals accumulate
                if (aiClient != null) {
                    String dreamsContent = readDreamsContext(userId);
                    if (countDreamBullets(dreamsContent) >= DREAM_CONSOLIDATION_THRESHOLD) {
                        log.info("Dream consolidation threshold ({}) reached for user={}, auto-triggering",
                                DREAM_CONSOLIDATION_THRESHOLD, userId);
                        consolidateDreamsWithLLM(userId);
                    }
                }
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

    // ── file helpers ───────────────────────────────────────────

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
                        return name.equals(prefix + ".md") || (name.startsWith(prefix + "-") && name.endsWith(".md"));
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
            return stream.filter(path -> path.getFileName().toString().equals(name)).toList();
        }
    }

    // ── markdown templates ─────────────────────────────────────

    private String dailyHeader(LocalDate date) {
        return "# " + DAY_FORMAT.format(date) + "\n\n"
                + "OpenClaw daily note. Append running context and observations here; promote only durable summaries to MEMORY.md.\n\n"
                + "## " + DAILY_SESSION_EVENTS + "\n\n"
                + "## " + DAILY_OBSERVATIONS + "\n\n"
                + "## " + DAILY_COMMITMENTS + "\n\n"
                + "## " + DAILY_CUSTOM + "\n";
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

    // ── markdown manipulation ──────────────────────────────────

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

    // ── text analysis ──────────────────────────────────────────

    private String addActionBoundary(String memoryText, String source) {
        String boundary = "Source: user message. Future behavior must respect any approval, expiry, owner, or safe-to-act condition stated here.";
        if (containsAny(source, "临时", "今天", "明天", "到期", "temporary", "until", "expires")) {
            boundary += " This appears temporary; do not treat it as permanent without confirmation.";
        }
        return memoryText + " (" + boundary + ")";
    }

    private boolean containsDurableSaveIntent(String text) {
        return containsAny(text, "长期记忆", "记住", "remember", "memory")
                && containsAny(text, "存入", "保存", "加入", "记到", "记入", "记住", "save", "store", "remember");
    }

    private String stripMemorySavePhrases(String text) {
        String cleaned = text;
        for (String phrase : new String[]{"记入长期记忆中", "记入长期记忆里", "存入长期记忆中", "存入长期记忆里",
                "保存到长期记忆中", "保存到长期记忆里", "加入长期记忆中", "加入长期记忆里",
                "记到长期记忆中", "记到长期记忆里", "长期记忆中", "长期记忆里",
                "记入长期记忆", "存入长期记忆", "保存到长期记忆", "加入长期记忆", "记到长期记忆",
                "长期记住", "长期记忆", "请", "帮我", "把", "将",
                "remember that", "Remember that"}) {
            cleaned = cleaned.replace(phrase, "");
        }
        return cleaned.replaceAll("[，。]", "").trim();
    }

    private String classifyMemorySection(String memoryText, String sourceText) {
        String all = memoryText + "\n" + sourceText;
        if (looksActionSensitive(all)) {
            return SEC_ACTION_BOUNDARIES;
        }
        if (containsAny(all, "喜欢", "不喜欢", "偏好", "习惯", "prefer", "preference", "style")) {
            return SEC_PREFERENCES;
        }
        if (containsAny(all, "项目", "平台", "代码", "project", "repo", "platform")) {
            return SEC_PROJECTS;
        }
        if (containsAny(all, "决定", "选择", "方案", "decision", "decided")) {
            return SEC_DECISIONS;
        }
        return SEC_FACTS;
    }

    private boolean looksActionSensitive(String text) {
        return containsAny(text,
                "批准", "允许", "确认", "删除", "到期", "临时", "不要", "禁止", "移交", "等到",
                "approval", "permission", "confirm", "delete", "expires", "temporary", "until",
                "do not", "handoff", "safe to act");
    }

    private boolean looksLikeOperationalEvent(String userMessage, String assistantReply) {
        return containsAny(userMessage,
                "帮我", "请", "优化", "修复", "删除", "创建", "建立", "生成", "写", "查询", "分析", "总结",
                "fix", "create", "delete", "write", "summarize", "analyze")
                || containsAny(assistantReply, "已", "完成", "已完成", "处理完", "done", "completed", "fixed", "created");
    }

    private boolean looksLikeObservation(String text) {
        return containsAny(text,
                "我喜欢", "我不喜欢", "我是", "我的", "偏好", "习惯", "重要", "上下文",
                "prefer", "preference", "remember", "important");
    }

    private boolean looksLikeCommitment(String text) {
        return containsAny(text,
                "待办", "提醒", "临时", "稍后", "一会", "明天", "后天", "今天", "计划", "任务",
                "todo", "remind", "temporary", "later", "tomorrow");
    }

    private String extractRequestedSection(String text, String memoryKeyword) {
        if (isBlank(text) || isBlank(memoryKeyword) || !text.contains(memoryKeyword)) {
            return null;
        }
        if ("每日记忆".equals(memoryKeyword)
                && containsAny(text, "记录", "记到", "写入", "存入", "保存")) {
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
            sb.append("result=").append(sanitizeOneLine(assistant));
        }
        return sb.toString();
    }

    private int firstSplitIndex(String text) {
        if (isBlank(text)) {
            return -1;
        }
        int split = text.indexOf(':');
        if (split < 0) {
            split = text.indexOf('：');
        }
        return split;
    }

    // ── utilities ──────────────────────────────────────────────

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
        return content.lines()
                .map(OpenClawConversationMemory::sanitizeOneLine)
                .anyMatch(line -> line.startsWith("- ") && line.contains(normalized));
    }

    private boolean isVagueMemoryPhrase(String cleaned) {
        if (isBlank(cleaned) || cleaned.length() <= 2) {
            return true;
        }
        return cleaned.matches("[我你他她]的[\\u4e00-\\u9fff]{1,4}") && cleaned.length() < 6;
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
        return sanitizeOneLine(value).replace("#", "").replace("|", "").trim();
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
        return text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
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

    // ── inner types ────────────────────────────────────────────

    private record DurableMemory(String text) {
    }

    private record FileCacheEntry(String content, long cachedAt, FileMtimes fileMtimes) {
    }

    private record FileMtimes(long memory, long dreams, long latestDaily) {
    }

    private static class SessionSlot {
        final Deque<ChatRequest.Message> messages = new ArrayDeque<>();
        volatile List<String> latestImageDataUrls;
        volatile String latestImageSummary;
        volatile long lastAccess;

        SessionSlot(long lastAccess) {
            this.lastAccess = lastAccess;
        }
    }
}
