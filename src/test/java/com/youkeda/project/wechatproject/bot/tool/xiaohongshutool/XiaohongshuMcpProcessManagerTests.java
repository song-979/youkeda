package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XiaohongshuMcpProcessManagerTests {

    @TempDir
    Path tempDir;

    @Test
    void processDefaultsUseBundledRuntimeWithNonFatalStartupErrors() {
        XiaohongshuProperties properties = new XiaohongshuProperties();

        assertThat(properties.getEndpoint()).isEqualTo("http://127.0.0.1:18060/mcp");
        assertThat(properties.getProcess().isAutoStart()).isTrue();
        assertThat(properties.getProcess().getCommand()).containsExactly("auto");
        assertThat(properties.getProcess().getStartupTimeoutMs()).isEqualTo(30000);
        assertThat(properties.getProcess().getStopTimeoutMs()).isEqualTo(5000);
        assertThat(properties.getProcess().getProbeTimeoutMs()).isEqualTo(1000);
        assertThat(properties.getProcess().isFailOnStartupError()).isFalse();
    }

    @Test
    void doesNotStartWhenAutoStartDisabled() {
        XiaohongshuProperties properties = propertiesWithCommand();
        properties.getProcess().setAutoStart(false);
        SequenceProbe probe = new SequenceProbe(false);
        CapturingLauncher launcher = new CapturingLauncher(new FakeProcess());
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(properties, probe, launcher);

        manager.start();

        assertThat(manager.isRunning()).isFalse();
        assertThat(probe.calls).isZero();
        assertThat(launcher.calls).isZero();
    }

    @Test
    void reusesExistingEndpointWhenItIsAlreadyAvailable() {
        XiaohongshuProperties properties = propertiesWithCommand();
        SequenceProbe probe = new SequenceProbe(true);
        CapturingLauncher launcher = new CapturingLauncher(new FakeProcess());
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(properties, probe, launcher);

        manager.start();
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        assertThat(probe.calls).isEqualTo(1);
        assertThat(launcher.calls).isZero();
    }

    @Test
    void startsConfiguredCommandWhenEndpointIsUnavailable() {
        XiaohongshuProperties properties = propertiesWithCommand();
        FakeProcess process = new FakeProcess();
        SequenceProbe probe = new SequenceProbe(false, true);
        CapturingLauncher launcher = new CapturingLauncher(process);
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(properties, probe, launcher);

        manager.start();

        assertThat(manager.isRunning()).isTrue();
        assertThat(launcher.calls).isEqualTo(1);
        assertThat(launcher.command).containsExactly("go", "run", ".");
        assertThat(launcher.workingDirectory).isEqualTo(Path.of("D:/xhs-mcp"));
        assertThat(launcher.environment).containsEntry("XHS_PORT", "18060");
        assertThat(launcher.redirectOutput).isTrue();
        assertThat(process.destroyed).isFalse();
    }

    @Test
    void autoCommandStartsBundledRuntimeForDetectedPlatform() throws Exception {
        Path executable = tempDir.resolve("tools/xiaohongshu-mcp/windows/xiaohongshu-mcp-windows-amd64.exe");
        Files.createDirectories(executable.getParent());
        Files.createFile(executable);
        XiaohongshuProperties properties = propertiesWithAutoCommand();
        SequenceProbe probe = new SequenceProbe(false, true);
        CapturingLauncher launcher = new CapturingLauncher(new FakeProcess());
        XiaohongshuMcpProcessManager.RuntimeResolver runtimeResolver =
                new XiaohongshuMcpProcessManager.BundledRuntimeResolver(
                        tempDir,
                        () -> new XiaohongshuMcpProcessManager.RuntimePlatform("windows", "amd64"));
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(
                properties, probe, launcher, runtimeResolver);

        manager.start();

        assertThat(manager.isRunning()).isTrue();
        assertThat(launcher.command).containsExactly(executable.toAbsolutePath().normalize().toString());
        assertThat(launcher.workingDirectory).isEqualTo(executable.getParent().toAbsolutePath().normalize());
    }

    @Test
    void autoCommandSkipsStartupWhenBundledRuntimeIsMissingAndStartupErrorsAreNonFatal() {
        XiaohongshuProperties properties = propertiesWithAutoCommand();
        SequenceProbe probe = new SequenceProbe(false);
        CapturingLauncher launcher = new CapturingLauncher(new FakeProcess());
        XiaohongshuMcpProcessManager.RuntimeResolver runtimeResolver =
                new XiaohongshuMcpProcessManager.BundledRuntimeResolver(
                        tempDir,
                        () -> new XiaohongshuMcpProcessManager.RuntimePlatform("windows", "amd64"));
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(
                properties, probe, launcher, runtimeResolver);

        manager.start();

        assertThat(manager.isRunning()).isFalse();
        assertThat(launcher.calls).isZero();
    }

    @Test
    void autoCommandFailsStartupWhenBundledRuntimeIsMissingAndStartupErrorsAreFatal() {
        XiaohongshuProperties properties = propertiesWithAutoCommand();
        properties.getProcess().setFailOnStartupError(true);
        XiaohongshuMcpProcessManager.RuntimeResolver runtimeResolver =
                new XiaohongshuMcpProcessManager.BundledRuntimeResolver(
                        tempDir,
                        () -> new XiaohongshuMcpProcessManager.RuntimePlatform("windows", "amd64"));
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(
                properties,
                new SequenceProbe(false),
                new CapturingLauncher(new FakeProcess()),
                runtimeResolver);

        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bundled Xiaohongshu MCP runtime was not found");
    }

    @Test
    void stopsOnlyTheOwnedProcess() {
        XiaohongshuProperties properties = propertiesWithCommand();
        FakeProcess process = new FakeProcess();
        XiaohongshuMcpProcessManager manager = new XiaohongshuMcpProcessManager(
                properties,
                new SequenceProbe(false, true),
                new CapturingLauncher(process));

        manager.start();
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        assertThat(process.destroyed).isTrue();
    }

    private static XiaohongshuProperties propertiesWithCommand() {
        XiaohongshuProperties properties = new XiaohongshuProperties();
        XiaohongshuProperties.ManagedProcessProperties process = properties.getProcess();
        process.setAutoStart(true);
        process.setCommand(List.of("go", "run", "."));
        process.setWorkingDirectory("D:/xhs-mcp");
        process.setEnvironment(Map.of("XHS_PORT", "18060"));
        process.setStartupTimeoutMs(1000);
        process.setStopTimeoutMs(1000);
        return properties;
    }

    private static XiaohongshuProperties propertiesWithAutoCommand() {
        XiaohongshuProperties properties = new XiaohongshuProperties();
        XiaohongshuProperties.ManagedProcessProperties process = properties.getProcess();
        process.setAutoStart(true);
        process.setCommand(List.of("auto"));
        process.setWorkingDirectory("");
        process.setStartupTimeoutMs(1000);
        process.setStopTimeoutMs(1000);
        return properties;
    }

    private static class SequenceProbe implements XiaohongshuMcpProcessManager.EndpointProbe {

        private final ArrayDeque<Boolean> results;
        private int calls;

        SequenceProbe(Boolean... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public boolean isAvailable(String endpoint, int timeoutMs) {
            calls++;
            return !results.isEmpty() && results.removeFirst();
        }
    }

    private static class CapturingLauncher implements XiaohongshuMcpProcessManager.ProcessLauncher {

        private final Process process;
        private int calls;
        private List<String> command;
        private Path workingDirectory;
        private Map<String, String> environment;
        private boolean redirectOutput;

        CapturingLauncher(Process process) {
            this.process = process;
        }

        @Override
        public Process start(List<String> command,
                             Path workingDirectory,
                             Map<String, String> environment,
                             boolean redirectOutput) {
            calls++;
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.environment = environment;
            this.redirectOutput = redirectOutput;
            return process;
        }
    }

    private static class FakeProcess extends Process {

        private boolean alive = true;
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
