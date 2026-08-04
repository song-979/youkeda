package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "agent.speech.enabled=false",
        "agent.tools.xiaohongshu.enabled=true",
        "agent.tools.xiaohongshu.endpoint=https://mcp.example.com/xhs",
        "agent.tools.xiaohongshu.process.auto-start=false"
})
class XiaohongshuToolWiringEnabledTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolRuntime toolRuntime;

    @Autowired
    @Qualifier("browserToolRuntime")
    private ToolRuntime browserToolRuntime;

    @Test
    void xiaohongshuToolsRegisterOnlyInBrowserRuntimeWhenEnabled() {
        assertThat(context.getBean(XiaohongshuTools.class)).isNotNull();
        assertThat(context.getBean(XiaohongshuMcpProcessManager.class)).isNotNull();
        assertThat(toolRuntime.tools()).noneMatch(tool -> tool instanceof XiaohongshuTools);
        assertThat(toolRuntime.getCategorySummary()).doesNotContain("xiaohongshu");
        assertThat(browserToolRuntime.tools()).anyMatch(tool -> tool instanceof XiaohongshuTools);
        assertThat(browserToolRuntime.getCategorySummary()).contains("xiaohongshu");
    }
}
