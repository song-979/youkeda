package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void xiaohongshuToolsRegisterWhenEnabled() {
        assertThat(context.getBean(XiaohongshuTools.class)).isNotNull();
        assertThat(context.getBean(XiaohongshuMcpProcessManager.class)).isNotNull();
        assertThat(toolRuntime.tools()).anyMatch(tool -> tool instanceof XiaohongshuTools);
        assertThat(toolRuntime.getCategorySummary()).contains("xiaohongshu");
    }
}
