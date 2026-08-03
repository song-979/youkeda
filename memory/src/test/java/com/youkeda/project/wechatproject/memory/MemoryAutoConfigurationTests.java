package com.youkeda.project.wechatproject.memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAutoConfigurationTests {

    @TempDir
    Path tempDir;

    @Test
    void createsConversationMemoryFromDedicatedMemoryModule() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryAutoConfiguration.class)
                .withPropertyValues(
                        "agent.memory.enabled=true",
                        "agent.memory.vector-enabled=false",
                        "agent.memory.base-path=" + tempDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConversationMemory.class);
                    assertThat(context.getBean(ConversationMemory.class))
                            .isInstanceOf(OpenClawConversationMemory.class);
                });
    }

    @Test
    void backsOffWhenAiIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryAutoConfiguration.class)
                .withPropertyValues("agent.memory.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ConversationMemory.class));
    }
}
