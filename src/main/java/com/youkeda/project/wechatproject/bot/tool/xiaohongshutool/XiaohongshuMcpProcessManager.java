package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops a local Xiaohongshu MCP subprocess when configured.
 */
public class XiaohongshuMcpProcessManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(XiaohongshuMcpProcessManager.class);
    private static final long PROBE_INTERVAL_MS = 500L;
    private static final String AUTO_COMMAND = "auto";

    private final XiaohongshuProperties properties;
    private final EndpointProbe endpointProbe;
    private final ProcessLauncher processLauncher;
    private final RuntimeResolver runtimeResolver;

    private volatile boolean running;
    private volatile Process ownedProcess;

    public XiaohongshuMcpProcessManager(XiaohongshuProperties properties) {
        this(properties, new SocketEndpointProbe(), new DefaultProcessLauncher(), new BundledRuntimeResolver());
    }

    XiaohongshuMcpProcessManager(XiaohongshuProperties properties,
                                 EndpointProbe endpointProbe,
                                 ProcessLauncher processLauncher) {
        this(properties, endpointProbe, processLauncher, new BundledRuntimeResolver());
    }

    XiaohongshuMcpProcessManager(XiaohongshuProperties properties,
                                 EndpointProbe endpointProbe,
                                 ProcessLauncher processLauncher,
                                 RuntimeResolver runtimeResolver) {
        this.properties = properties;
        this.endpointProbe = endpointProbe;
        this.processLauncher = processLauncher;
        this.runtimeResolver = runtimeResolver;
    }

    @Override
    public boolean isAutoStartup() {
        return processProperties().isAutoStart();
    }

    @Override
    public void start() {
        XiaohongshuProperties.ManagedProcessProperties processProperties = processProperties();
        if (!processProperties.isAutoStart()) {
            return;
        }

        if (isEndpointAvailable(processProperties)) {
            running = true;
            log.info("Xiaohongshu MCP endpoint is already available, endpoint: {}", properties.getEndpoint());
            return;
        }

        ProcessCommand processCommand = resolveProcessCommand(processProperties);
        List<String> command = processCommand.command();
        if (command.isEmpty()) {
            handleStartupError(processCommand.errorMessage(), null);
            return;
        }

        try {
            ownedProcess = processLauncher.start(
                    command,
                    processCommand.workingDirectory(),
                    processProperties.getEnvironment(),
                    processProperties.isRedirectOutput());
            log.info("Started Xiaohongshu MCP process, pid: {}, endpoint: {}",
                    safePid(ownedProcess), properties.getEndpoint());
            waitForEndpoint(processProperties);
            running = true;
        } catch (Exception e) {
            handleStartupError("Failed to start Xiaohongshu MCP process", e);
        }
    }

    @Override
    public void stop() {
        Process process = ownedProcess;
        ownedProcess = null;
        running = false;
        if (process == null) {
            return;
        }

        process.destroy();
        try {
            long timeoutMs = Math.max(1, processProperties().getStopTimeoutMs());
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            }
            log.info("Stopped Xiaohongshu MCP process, pid: {}", safePid(process));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            log.warn("Interrupted while stopping Xiaohongshu MCP process, pid: {}", safePid(process));
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    private void waitForEndpoint(XiaohongshuProperties.ManagedProcessProperties processProperties)
            throws InterruptedException {
        long timeoutMs = Math.max(1, processProperties.getStartupTimeoutMs());
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (isEndpointAvailable(processProperties)) {
                return;
            }
            Process process = ownedProcess;
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("Xiaohongshu MCP process exited before endpoint became available");
            }
            Thread.sleep(PROBE_INTERVAL_MS);
        }
        throw new IllegalStateException("Timed out waiting for Xiaohongshu MCP endpoint: " + properties.getEndpoint());
    }

    private boolean isEndpointAvailable(XiaohongshuProperties.ManagedProcessProperties processProperties) {
        return endpointProbe.isAvailable(properties.getEndpoint(), Math.max(1, processProperties.getProbeTimeoutMs()));
    }

    private void handleStartupError(String message, Exception cause) {
        if (cause == null) {
            log.warn("{}.", message);
        } else {
            log.warn("{}: {}", message, cause.getMessage(), cause);
        }
        if (processProperties().isFailOnStartupError()) {
            throw cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
        }
    }

    private XiaohongshuProperties.ManagedProcessProperties processProperties() {
        return properties.getProcess();
    }

    private static List<String> normalizedCommand(List<String> command) {
        if (command == null) {
            return List.of();
        }
        return command.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .toList();
    }

    private ProcessCommand resolveProcessCommand(XiaohongshuProperties.ManagedProcessProperties processProperties) {
        List<String> command = normalizedCommand(processProperties.getCommand());
        Path configuredWorkingDirectory = normalizedWorkingDirectory(processProperties.getWorkingDirectory());
        if (!isAutoCommand(command)) {
            return new ProcessCommand(
                    command,
                    configuredWorkingDirectory,
                    "Xiaohongshu MCP auto-start is enabled but no process command is configured");
        }

        Optional<RuntimeCommand> runtimeCommand = runtimeResolver.resolve();
        if (runtimeCommand.isEmpty()) {
            return new ProcessCommand(
                    List.of(),
                    null,
                    "Bundled Xiaohongshu MCP runtime was not found under tools/xiaohongshu-mcp for this platform");
        }

        RuntimeCommand resolved = runtimeCommand.get();
        Path workingDirectory = configuredWorkingDirectory != null
                ? configuredWorkingDirectory
                : resolved.workingDirectory();
        return new ProcessCommand(resolved.command(), workingDirectory, "");
    }

    private static boolean isAutoCommand(List<String> command) {
        return command.size() == 1 && AUTO_COMMAND.equalsIgnoreCase(command.getFirst());
    }

    private static Path normalizedWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }
        return Path.of(workingDirectory.trim());
    }

    private static String safePid(Process process) {
        if (process == null) {
            return "unknown";
        }
        try {
            return Long.toString(process.pid());
        } catch (UnsupportedOperationException e) {
            return "unknown";
        }
    }

    interface EndpointProbe {
        boolean isAvailable(String endpoint, int timeoutMs);
    }

    interface ProcessLauncher {
        Process start(List<String> command,
                      Path workingDirectory,
                      Map<String, String> environment,
                      boolean redirectOutput) throws IOException;
    }

    interface RuntimeResolver {
        Optional<RuntimeCommand> resolve();
    }

    interface RuntimePlatformDetector {
        RuntimePlatform detect();
    }

    record RuntimePlatform(String operatingSystem, String architecture) {
    }

    record RuntimeCommand(List<String> command, Path workingDirectory) {
    }

    private record ProcessCommand(List<String> command, Path workingDirectory, String errorMessage) {
    }

    static class SocketEndpointProbe implements EndpointProbe {

        @Override
        public boolean isAvailable(String endpoint, int timeoutMs) {
            if (endpoint == null || endpoint.isBlank()) {
                return false;
            }
            try {
                URI uri = URI.create(endpoint.trim());
                String host = uri.getHost();
                int port = resolvePort(uri);
                if (host == null || port <= 0) {
                    return false;
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), timeoutMs);
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }

        private static int resolvePort(URI uri) {
            if (uri.getPort() > 0) {
                return uri.getPort();
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return 80;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return 443;
            }
            return -1;
        }
    }

    static class DefaultProcessLauncher implements ProcessLauncher {

        @Override
        public Process start(List<String> command,
                             Path workingDirectory,
                             Map<String, String> environment,
                             boolean redirectOutput) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            if (redirectOutput) {
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            return builder.start();
        }
    }

    static class BundledRuntimeResolver implements RuntimeResolver {

        private final Path baseDirectory;
        private final RuntimePlatformDetector platformDetector;

        BundledRuntimeResolver() {
            this(Path.of("").toAbsolutePath(), new SystemRuntimePlatformDetector());
        }

        BundledRuntimeResolver(Path baseDirectory, RuntimePlatformDetector platformDetector) {
            this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
            this.platformDetector = platformDetector;
        }

        @Override
        public Optional<RuntimeCommand> resolve() {
            RuntimePlatform platform = platformDetector.detect();
            for (String executableName : executableNames(platform)) {
                Path executable = baseDirectory
                        .resolve("tools")
                        .resolve("xiaohongshu-mcp")
                        .resolve(platform.operatingSystem())
                        .resolve(executableName)
                        .toAbsolutePath()
                        .normalize();
                if (Files.isRegularFile(executable)) {
                    return Optional.of(new RuntimeCommand(List.of(executable.toString()), executable.getParent()));
                }
            }
            return Optional.empty();
        }

        private static List<String> executableNames(RuntimePlatform platform) {
            String operatingSystem = platform.operatingSystem();
            String architecture = platform.architecture();
            if ("windows".equals(operatingSystem)) {
                return List.of("xiaohongshu-mcp-windows-" + architecture + ".exe");
            }
            if ("macos".equals(operatingSystem)) {
                return List.of("xiaohongshu-mcp-darwin-" + architecture);
            }
            if ("linux".equals(operatingSystem)) {
                return List.of("xiaohongshu-mcp-linux-" + architecture);
            }
            return List.of();
        }
    }

    static class SystemRuntimePlatformDetector implements RuntimePlatformDetector {

        @Override
        public RuntimePlatform detect() {
            return new RuntimePlatform(normalizeOperatingSystem(), normalizeArchitecture());
        }

        private static String normalizeOperatingSystem() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                return "windows";
            }
            if (osName.contains("mac") || osName.contains("darwin")) {
                return "macos";
            }
            if (osName.contains("linux")) {
                return "linux";
            }
            return osName.replaceAll("[^a-z0-9]+", "");
        }

        private static String normalizeArchitecture() {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (arch.equals("x86_64") || arch.equals("x64") || arch.equals("amd64")) {
                return "amd64";
            }
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "arm64";
            }
            return arch.replaceAll("[^a-z0-9]+", "");
        }
    }
}
