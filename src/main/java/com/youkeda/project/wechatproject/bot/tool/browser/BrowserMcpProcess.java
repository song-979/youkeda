package com.youkeda.project.wechatproject.bot.tool.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of a chrome-devtools-mcp Node.js subprocess.
 * Communicates with the MCP server via stdin/stdout.
 *
 * <p>Startup health: after launching, waits for the process to stabilize before
 * reporting success. If the process exits immediately (e.g. missing modules),
 * the startup failure is reported as an IOException with stderr diagnostics.</p>
 *
 * <p>Crash cooldown: consecutive startup failures disable automatic restarts for
 * a cooldown period to avoid retry storms.</p>
 */
public class BrowserMcpProcess {

    private static final Logger log = LoggerFactory.getLogger(BrowserMcpProcess.class);
    private static final int GRACEFUL_STOP_SECONDS = 5;
    private static final int STDERR_BUFFER_LINES = 50;

    public enum ProcessHealth { HEALTHY, STARTING, CRASHED, DEAD }

    private final BrowserMcpProperties properties;
    private Process process;
    private InputStream processStdout;
    private InputStream processStderr;
    private OutputStream processStdin;
    private volatile boolean started;
    private volatile ProcessHealth health = ProcessHealth.DEAD;
    private volatile long lastActivityNanos;
    private volatile int consecutiveStartupFailures;
    private String lastCrashReason;
    private final List<String> stderrBuffer = new ArrayList<>(STDERR_BUFFER_LINES);

    public BrowserMcpProcess(BrowserMcpProperties properties) {
        this.properties = properties;
    }

    public synchronized void start() throws IOException {
        if (started && process != null && process.isAlive()) {
            log.debug("chrome-devtools-mcp process already running (pid={})", process.pid());
            return;
        }

        List<String> command = buildCommand();
        log.info("Starting chrome-devtools-mcp: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        // Separate stderr for crash diagnostics
        pb.redirectErrorStream(false);

        pb.environment().put("CHROME_DEVTOOLS_MCP_NO_USAGE_STATISTICS", "true");
        pb.environment().put("CHROME_DEVTOOLS_MCP_NO_UPDATE_CHECKS", "true");

        health = ProcessHealth.STARTING;
        try {
            process = pb.start();
        } catch (IOException e) {
            health = ProcessHealth.CRASHED;
            lastCrashReason = "Failed to spawn process: " + e.getMessage();
            log.error("chrome-devtools-mcp failed to start: {}", lastCrashReason);
            throw new IOException("Browser process failed to start: " + e.getMessage(), e);
        }

        processStdout = process.getInputStream();
        processStderr = process.getErrorStream();
        processStdin = process.getOutputStream();
        started = true;
        lastActivityNanos = System.nanoTime();

        // Start stderr reader thread for crash diagnostics
        Thread stderrReader = new Thread(this::captureStderr, "mcp-stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Startup health check: wait briefly for the process to stabilize
        long startupTimeoutMs = properties.getStartupHealthCheckTimeout().toMillis();
        try {
            Thread.sleep(Math.min(startupTimeoutMs, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            health = ProcessHealth.CRASHED;
            consecutiveStartupFailures++;
            String stderrSummary = getStderrSummary();
            lastCrashReason = "Process exited immediately (exit code=" + exitCode
                    + ", startupFailures=" + consecutiveStartupFailures + ")";
            log.error("chrome-devtools-mcp startup failed after {}ms: exitCode={}, consecutiveFailures={}\nstderr: {}",
                    startupTimeoutMs, exitCode, consecutiveStartupFailures, stderrSummary);
            closeStreams();
            process = null;
            started = false;
            throw new IOException("Browser process crashed on startup (exit code " + exitCode
                    + "). Check chrome-devtools-mcp build. stderr: " + stderrSummary);
        }

        health = ProcessHealth.HEALTHY;
        log.info("chrome-devtools-mcp started (pid={}), health check OK", process.pid());
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getNodePath());
        cmd.add(properties.getMcpEntryPoint());

        if (properties.isHeadless()) {
            cmd.add("--headless=true");
        }
        cmd.add("--isolated");
        if (properties.getUserDataDir() != null && !properties.getUserDataDir().isEmpty()) {
            cmd.add("--user-data-dir=" + properties.getUserDataDir());
        }
        cmd.add("--no-usage-statistics");

        return cmd;
    }

    public synchronized void stop() {
        if (process == null) return;
        started = false;
        health = ProcessHealth.DEAD;

        try {
            if (process.isAlive()) {
                log.info("Stopping chrome-devtools-mcp (pid={})", process.pid());
                process.destroy();
                boolean terminated = process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS);
                if (!terminated) {
                    log.warn("chrome-devtools-mcp did not terminate gracefully, force-killing");
                    process.destroyForcibly();
                }
            } else {
                // Process already dead — capture diagnostics
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    String stderr = getStderrSummary();
                    lastCrashReason = "exited with code=" + exitCode + ", stderr=" + stderr;
                    log.warn("chrome-devtools-mcp abnormal exit (code={}), stderr={}", exitCode, stderr);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            closeStreams();
            process = null;
        }
    }

    /**
     * Restart the process. If there have been multiple consecutive startup
     * failures, throws immediately to enforce cooldown — avoids retry storms
     * against a fundamentally broken build.
     */
    public synchronized void restart() throws IOException {
        if (consecutiveStartupFailures >= properties.getMaxConsecutiveStartupFailures()) {
            long lastCrashNanos = lastActivityNanos;
            long elapsedSinceLast = System.nanoTime() - lastCrashNanos;
            long cooldownNanos = properties.getCrashCooldown().toNanos();
            if (elapsedSinceLast < cooldownNanos) {
                long remainingSec = TimeUnit.NANOSECONDS.toSeconds(cooldownNanos - elapsedSinceLast);
                throw new BrowserMcpException("PROCESS_DEAD",
                        "浏览器进程启动连续失败 " + consecutiveStartupFailures + " 次，"
                                + "冷却中（剩余 " + remainingSec + " 秒）。"
                                + "请检查 chrome-devtools-mcp 构建是否正确。最后一��错误: " + lastCrashReason);
            }
            // Cooldown expired, reset and try again
            consecutiveStartupFailures = 0;
        }
        stop();
        start();
    }

    public boolean isAlive() {
        return started && process != null && process.isAlive();
    }

    public ProcessHealth getHealth() {
        return health;
    }

    public int getConsecutiveStartupFailures() {
        return consecutiveStartupFailures;
    }

    public String getLastCrashReason() {
        return lastCrashReason;
    }

    /**
     * Record successful activity for idle timeout tracking.
     */
    public void markActivity() {
        lastActivityNanos = System.nanoTime();
    }

    public InputStream getStdout() {
        return processStdout;
    }

    public OutputStream getStdin() {
        return processStdin;
    }

    public long getPid() {
        return process != null ? process.pid() : -1;
    }

    /**
     * @return diagnostic summary for logging
     */
    public String getDiagnostics() {
        return "{health=" + health
                + ", pid=" + getPid()
                + ", startupFailures=" + consecutiveStartupFailures
                + (lastCrashReason != null ? ", lastCrash=\"" + lastCrashReason + "\"" : "")
                + "}";
    }

    /**
     * Background thread: captures last N lines of stderr for crash diagnostics.
     */
    private void captureStderr() {
        try {
            byte[] buf = new byte[4096];
            int len;
            while ((len = processStderr.read(buf)) != -1) {
                String text = new String(buf, 0, len);
                synchronized (stderrBuffer) {
                    for (String line : text.split("\n")) {
                        if (!line.isBlank()) {
                            stderrBuffer.add(line);
                            if (stderrBuffer.size() > STDERR_BUFFER_LINES) {
                                stderrBuffer.remove(0);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("stderr reader stopped: {}", e.getMessage());
        }
    }

    private String getStderrSummary() {
        synchronized (stderrBuffer) {
            if (stderrBuffer.isEmpty()) return "(empty)";
            return String.join(" | ", stderrBuffer);
        }
    }

    private void closeStreams() {
        try { if (processStdin != null) processStdin.close(); } catch (IOException ignored) {}
        try { if (processStdout != null) processStdout.close(); } catch (IOException ignored) {}
        try { if (processStderr != null) processStderr.close(); } catch (IOException ignored) {}
    }
}
