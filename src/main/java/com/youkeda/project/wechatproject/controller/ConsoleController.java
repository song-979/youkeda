package com.youkeda.project.wechatproject.controller;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.monitor.ChatLogRecorder;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import com.youkeda.project.wechatproject.bot.tool.ToolService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
public class ConsoleController {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> KEY_CONFIGS = List.of(
            "agent.ai.api-key",
            "agent.ai.image-gen-api-key",
            "agent.ai.intent-api-key",
            "agent.ai.memory-embedding-api-key",
            "agent.speech.api-key",
            "agent.tools.weather.amap-key",
            "agent.tools.weather.amap-private-key",
            "agent.tools.didi.api-key",
            "agent.tools.search.api-key",
            "agent.tools.webparse.api-key",
            "agent.tools.motou.api-key"
    );

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final ObjectProvider<ILinkClient> ilinkClientProvider;
    private final ObjectProvider<MessageBridge> messageBridgeProvider;
    private final ObjectProvider<ToolService.ProjectTool> toolProvider;
    private final ObjectProvider<ChatLogRecorder> chatLogRecorderProvider;
    private final Instant startedAt = Instant.now();

    public ConsoleController(ApplicationContext applicationContext,
                             Environment environment,
                             ObjectProvider<ILinkClient> ilinkClientProvider,
                             ObjectProvider<MessageBridge> messageBridgeProvider,
                             ObjectProvider<ToolService.ProjectTool> toolProvider,
                             ObjectProvider<ChatLogRecorder> chatLogRecorderProvider) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.ilinkClientProvider = ilinkClientProvider;
        this.messageBridgeProvider = messageBridgeProvider;
        this.toolProvider = toolProvider;
        this.chatLogRecorderProvider = chatLogRecorderProvider;
    }

    @GetMapping(value = "/console", produces = "text/html;charset=UTF-8")
    public String consolePage() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/console.html");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @GetMapping("/api/console/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary());
        body.put("runtime", runtime());
        body.put("services", services());
        body.put("tools", tools());
        body.put("chatLogs", chatLogs());
        body.put("dataFiles", dataFiles());
        body.put("configuration", configuration());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> services = services();
        long warnings = services.stream().filter(item -> !"ok".equals(item.get("state"))).count();
        summary.put("state", warnings == 0 ? "ok" : "warning");
        summary.put("label", warnings == 0 ? "运行正常" : "有项目需要查看");
        summary.put("message", warnings == 0
                ? "后端已启动，主要能力正在待命。"
                : "系统可以打开，但有部分能力关闭、未登录或缺少配置。");
        summary.put("checkedAt", TIME_FORMATTER.format(Instant.now()));
        summary.put("uptime", humanDuration(Duration.between(startedAt, Instant.now())));
        return summary;
    }

    private Map<String, Object> runtime() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "youkeda 微信 AI 助手");
        data.put("java", System.getProperty("java.version"));
        data.put("pid", processId());
        data.put("profiles", activeProfiles());
        data.put("port", environment.getProperty("server.port", "8080"));
        data.put("beanCount", applicationContext.getBeanDefinitionCount());
        data.put("threads", threadMXBean.getThreadCount());
        data.put("daemonThreads", threadMXBean.getDaemonThreadCount());
        data.put("heapUsed", bytes(heap.getUsed()));
        data.put("heapMax", bytes(heap.getMax()));
        data.put("memoryUsedPercent", percent(heap.getUsed(), heap.getMax()));
        data.put("freeMemory", bytes(runtime.freeMemory()));
        return data;
    }

    private List<Map<String, Object>> services() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(service("后端服务", "ok", "已启动", "Spring Boot 正在响应控制台请求。"));
        items.add(ilinkService());
        items.add(configuredService(
                "文本大模型",
                booleanProperty("agent.ai.enabled", true),
                "agent.ai.api-key",
                "模型：" + valueOrDefault("agent.ai.model", "未填写")));
        items.add(configuredService(
                "图片生成",
                booleanProperty("agent.ai.image-gen-enabled", true),
                firstNonBlankProperty("agent.ai.image-gen-api-key", "agent.ai.api-key"),
                "模型：" + valueOrDefault("agent.ai.image-gen-model", "未填写")));
        items.add(configuredService(
                "语音识别",
                booleanProperty("agent.speech.enabled", true) && booleanProperty("agent.speech.stt.enabled", true),
                "agent.speech.api-key",
                "模型：" + valueOrDefault("agent.speech.stt.model", "未填写")));
        items.add(configuredService(
                "语音输出",
                booleanProperty("agent.speech.enabled", true) && booleanProperty("agent.speech.tts.enabled", true),
                "agent.speech.api-key",
                "模型：" + valueOrDefault("agent.speech.tts.model", "未填写")));
        items.add(configuredService(
                "天气与地图",
                booleanProperty("agent.tools.weather.enabled", true),
                "agent.tools.weather.amap-key",
                "路线、周边、天气会共用高德相关配置。"));
        items.add(configuredService(
                "滴滴打车",
                booleanProperty("agent.tools.didi.enabled", true),
                "agent.tools.didi.api-key",
                "用于沙箱/虚拟订单链路。"));
        return items;
    }

    private Map<String, Object> ilinkService() {
        ILinkClient client = ilinkClientProvider.getIfAvailable();
        if (!booleanProperty("ilink.enabled", true)) {
            return service("微信 iLink", "off", "已关闭", "配置中关闭了微信连接。");
        }
        if (client == null) {
            return service("微信 iLink", "warning", "未创建", "当前进程没有 iLink 客户端，可能是配置关闭或启动未完成。");
        }
        try {
            if (client.isLoggedIn()) {
                String botId = client.getLoginContext() != null ? client.getLoginContext().getBotId() : "";
                return service("微信 iLink", "ok", "已登录", blank(botId) ? "微信机器人已连接。" : "botId：" + botId);
            }
            MessageBridge bridge = messageBridgeProvider.getIfAvailable();
            boolean hasQrCode = bridge != null && !blank(bridge.getQrcode());
            return service("微信 iLink", "warning", hasQrCode ? "等待扫码" : "未登录", hasQrCode
                    ? "二维码已生成，等待微信扫码。"
                    : "还没有登录，可打开 /setup 或 /ilink/qrcode。");
        } catch (Exception e) {
            return service("微信 iLink", "error", "检查失败", "iLink 状态读取失败。");
        }
    }

    private Map<String, Object> configuredService(String name, boolean enabled, String keyProperty, String detail) {
        if (!enabled) {
            return service(name, "off", "已关闭", detail);
        }
        if (!hasPropertyValue(keyProperty)) {
            return service(name, "warning", "缺少配置", detail + " 需要补充访问密钥。");
        }
        return service(name, "ok", "已配置", detail);
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        toolProvider.stream()
                .sorted(Comparator.comparing(tool -> tool.getClass().getSimpleName()))
                .forEach(tool -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", readableToolName(tool.getClass().getSimpleName()));
                    item.put("className", tool.getClass().getSimpleName());
                    item.put("category", blank(tool.category()) ? "未分组" : tool.category());
                    item.put("state", "ok");
                    item.put("label", "已加载");
                    tools.add(item);
                });
        if (tools.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", "工具系统");
            item.put("category", "全部");
            item.put("state", "warning");
            item.put("label", "没有加载工具 Bean");
            tools.add(item);
        }
        return tools;
    }

    private List<Map<String, Object>> chatLogs() {
        ChatLogRecorder recorder = chatLogRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return List.of();
        }
        return recorder.recent(60).stream().map(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("time", TIME_FORMATTER.format(entry.at()));
            item.put("direction", entry.direction());
            item.put("userId", entry.userId());
            item.put("kind", entry.kind());
            item.put("content", entry.content());
            item.put("status", entry.status());
            return item;
        }).toList();
    }

    private List<Map<String, Object>> dataFiles() {
        return List.of(
                fileStatus("本地配置", Path.of("config", "application-local.yaml"), "保存个人配置，不应提交。"),
                fileStatus("登录恢复", Path.of("data", "ilink-resume", "resume-context.json"), "用于下次启动恢复 iLink 上下文。"),
                fileStatus("定时任务", Path.of("data", "tool-automation", "automation.json"), "保存提醒和日程。"),
                fileStatus("记忆索引", Path.of("data", "memory", "memory-index.db"), "SQLite 记忆检索索引。"),
                directoryStatus("技能目录", Path.of("data", "skills"), "本地 Skill 文件。")
        );
    }

    private List<Map<String, Object>> configuration() {
        List<Map<String, Object>> items = new ArrayList<>();
        KEY_CONFIGS.forEach(key -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", readablePropertyName(key));
            item.put("property", key);
            item.put("state", hasPropertyValue(key) ? "ok" : "warning");
            item.put("label", hasPropertyValue(key) ? "已填写" : "未填写");
            item.put("value", hasPropertyValue(key) ? "已隐藏" : "");
            items.add(item);
        });
        addPlainConfig(items, "agent.ai.model", "文本模型");
        addPlainConfig(items, "agent.ai.image-gen-model", "图片模型");
        addPlainConfig(items, "agent.speech.tts.model", "语音模型");
        addPlainConfig(items, "agent.speech.stt.ffmpeg-path", "FFmpeg 路径");
        return items;
    }

    private void addPlainConfig(List<Map<String, Object>> items, String key, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        String value = environment.getProperty(key, "");
        item.put("name", name);
        item.put("property", key);
        item.put("state", blank(value) ? "warning" : "ok");
        item.put("label", blank(value) ? "未填写" : "已设置");
        item.put("value", value);
        items.add(item);
    }

    private Map<String, Object> fileStatus(String name, Path path, String detail) {
        Map<String, Object> item = baseFileItem(name, path, detail);
        if (!Files.exists(path)) {
            item.put("state", "warning");
            item.put("label", "未找到");
            item.put("size", "-");
            item.put("modifiedAt", "-");
            return item;
        }
        try {
            item.put("state", "ok");
            item.put("label", "已存在");
            item.put("size", bytes(Files.size(path)));
            item.put("modifiedAt", TIME_FORMATTER.format(Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException e) {
            item.put("state", "error");
            item.put("label", "读取失败");
            item.put("size", "-");
            item.put("modifiedAt", "-");
        }
        return item;
    }

    private Map<String, Object> directoryStatus(String name, Path path, String detail) {
        Map<String, Object> item = baseFileItem(name, path, detail);
        if (!Files.isDirectory(path)) {
            item.put("state", "warning");
            item.put("label", "未找到");
            item.put("size", "-");
            item.put("modifiedAt", "-");
            return item;
        }
        try (var stream = Files.list(path)) {
            long count = stream.count();
            item.put("state", "ok");
            item.put("label", count + " 项");
            item.put("size", "-");
            item.put("modifiedAt", TIME_FORMATTER.format(Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException e) {
            item.put("state", "error");
            item.put("label", "读取失败");
            item.put("size", "-");
            item.put("modifiedAt", "-");
        }
        return item;
    }

    private Map<String, Object> baseFileItem(String name, Path path, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("path", path.toString());
        item.put("detail", detail);
        return item;
    }

    private Map<String, Object> service(String name, String state, String label, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("state", state);
        item.put("label", label);
        item.put("detail", detail);
        return item;
    }

    private boolean booleanProperty(String key, boolean defaultValue) {
        return environment.getProperty(key, Boolean.class, defaultValue);
    }

    private boolean hasPropertyValue(String key) {
        return !blank(environment.getProperty(key));
    }

    private String firstNonBlankProperty(String... keys) {
        for (String key : keys) {
            if (hasPropertyValue(key)) {
                return key;
            }
        }
        return keys.length == 0 ? "" : keys[0];
    }

    private String valueOrDefault(String key, String fallback) {
        String value = environment.getProperty(key);
        return blank(value) ? fallback : value;
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(", ", profiles);
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static String bytes(long value) {
        if (value < 0) {
            return "不限制";
        }
        double number = value;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (number >= 1024 && unit < units.length - 1) {
            number /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", number, units[unit]);
    }

    private static int percent(long used, long max) {
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round((double) used * 100 / max);
    }

    private static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return minutes + " 分钟";
    }

    private static String readableToolName(String className) {
        return className
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("Tools", "工具")
                .replace("Tool", "工具")
                .trim();
    }

    private static String readablePropertyName(String property) {
        return switch (property) {
            case "agent.ai.api-key" -> "文本模型 Key";
            case "agent.ai.image-gen-api-key" -> "图片模型 Key";
            case "agent.ai.intent-api-key" -> "路由模型 Key";
            case "agent.ai.memory-embedding-api-key" -> "记忆向量 Key";
            case "agent.speech.api-key" -> "语音服务 Key";
            case "agent.tools.weather.amap-key" -> "高德 Web Key";
            case "agent.tools.weather.amap-private-key" -> "高德私钥";
            case "agent.tools.didi.api-key" -> "滴滴工具 Key";
            case "agent.tools.search.api-key" -> "搜索工具 Key";
            case "agent.tools.webparse.api-key" -> "网页解析 Key";
            case "agent.tools.motou.api-key" -> "图片修改 Key";
            default -> property;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
