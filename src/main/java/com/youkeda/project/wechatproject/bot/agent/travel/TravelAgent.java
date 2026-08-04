package com.youkeda.project.wechatproject.bot.agent.travel;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentContextAssembler;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.memory.AgentMemory;
import com.youkeda.project.wechatproject.bot.memory.FileBasedAgentMemory;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextPackage;
import com.youkeda.project.wechatproject.bot.context.ToolLoopContextRuntime;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import com.youkeda.project.wechatproject.bot.tool.chat.UserMessageTool;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.travel.AmapDirectionTools;
import com.youkeda.project.wechatproject.bot.tool.travel.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles weather, map/navigation, POI search, and ride-hailing through
 * Spring AI tool-calling with only travel-related tools.
 */
public class TravelAgent implements AgentUnit, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TravelAgent.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final String SYSTEM_PROMPT = """
            你是出行助手。你可以使用高德地图、天气、滴滴打车等工具来帮助用户。

            【滴滴打车流程——重要】
            当用户需要打车（叫车、打车、去某地需要车）时，遵循以下流程：
            1. 先调用 didi_taxi_estimate 预估价格
            2. 将预估结果（车型名称和价格）清楚地展示给用户
            3. 输出 __PAUSED__: 标记暂停，格式如下：
               __PAUSED__:已为您查询到以下车型预估价格：
               - 特惠快车：约¥XX元
               - 快车：约¥XX元
               （列出所有可用车型）
               请问您要选择哪个车型？
            4. 不要继续创建订单！系统会自动保存进度，等用户回复选哪个车型后恢复执行。
            5. 系统恢复后，你会收到用户的车型选择，届时再调用 didi_taxi_create_order 创建订单。

            【其他出行需求】
            天气查询、地点搜索、周边POI、路线规划、导航、地图等直接返回结果即可，不需要 PAUSED。正常用中文回复。
            """;
    private final ChatClient toolChatClient;
    private final String skillsSummary;
    private final AgentMemory agentMemory;
    private final long timeoutSeconds;
    private final ContextEngineeringService contextEngineeringService;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public TravelAgent(ToolChatClientFactory travelToolChatClientFactory) {
        this(travelToolChatClientFactory, "", null);
    }

    public TravelAgent(ToolChatClientFactory travelToolChatClientFactory, String skillsSummary) {
        this(travelToolChatClientFactory, skillsSummary, null);
    }

    public TravelAgent(ToolChatClientFactory travelToolChatClientFactory, String skillsSummary,
                       AgentMemory agentMemory) {
        this(travelToolChatClientFactory, skillsSummary, agentMemory, DEFAULT_TIMEOUT_SECONDS);
    }

    public TravelAgent(ToolChatClientFactory travelToolChatClientFactory, String skillsSummary,
                       AgentMemory agentMemory, long timeoutSeconds) {
        this(travelToolChatClientFactory, skillsSummary, agentMemory, timeoutSeconds, null);
    }

    public TravelAgent(ToolChatClientFactory travelToolChatClientFactory, String skillsSummary,
                       AgentMemory agentMemory, long timeoutSeconds,
                       ContextEngineeringService contextEngineeringService) {
        this.toolChatClient = travelToolChatClientFactory != null
                ? travelToolChatClientFactory.create() : null;
        this.skillsSummary = skillsSummary != null ? skillsSummary : "";
        this.agentMemory = agentMemory;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.contextEngineeringService = contextEngineeringService;
    }

    @Override
    public void destroy() {
        toolExecutor.shutdownNow();
    }

    @Override
    public String getName() {
        return "TRAVEL";
    }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability(
                "travel-services",
                "出行助手：处理天气查询、地点搜索、周边POI搜索、路线规划（步行/公交/驾车/骑行）、导航、静态地图生成、滴滴打车（价格预估/叫车/订单查询/取消/司机位置）等出行相关需求。",
                List.of("weather", "place-search", "nearby-search", "route-planning",
                        "map-navigation", "geocoding", "taxi-hailing", "ride-estimate"),
                "text",
                List.of("天气", "附近", "地点", "导航", "路线", "打车", "叫车", "怎么走",
                        "周边", "坐标", "POI", "map", "地址", "在哪", "搜索地点",
                        "查询天气", "天气预报", "出行", "公交", "地铁", "步行", "骑行",
                        "高德", "位置", "定位"),
                true
        );
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        log.info("TravelAgent executing task: instruction={}", task.instruction());
        String userId = stringParam(task.parameters(), "userId");

        if (toolChatClient == null) {
            return AgentResult.success(task.taskId(),
                    "出行服务暂不可用。",
                    "Travel agent has no tool client available.");
        }

        try {
            String effectiveSystemPrompt = buildEffectiveSystemPrompt(userId, task.instruction());
            ContextPackage context = AgentContextAssembler.build(
                    contextEngineeringService, task, effectiveSystemPrompt, agentMemory);
            var prompt = toolChatClient.prompt()
                    .messages(AgentContextAssembler.toSpringMessages(context, List.of()));
            var transcriptReportRef = new AtomicReference<ToolLoopContextRuntime.Report>();
            Future<String> future = toolExecutor.submit(() -> {
                DiDiTaxiTools.setCurrentUser(userId);
                SkillTools.setCurrentAgent(getName());
                try {
                    return prompt.call().content();
                } finally {
                    transcriptReportRef.set(ToolLoopContextRuntime.drain());
                    SkillTools.clearCurrentAgent();
                    DiDiTaxiTools.clearCurrentUser();
                }
            });
            String response;
            try {
                response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return AgentResult.failed(task.taskId(),
                        "出行服务查询超时（" + timeoutSeconds + "秒），请稍后重试。",
                        AgentResult.ErrorKind.TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return AgentResult.failed(task.taskId(), "出行服务查询被中断。", AgentResult.ErrorKind.TIMEOUT);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(cause != null ? cause : e);
            }

            // Drain map images cached by Amap tools during this execution
            List<byte[]> mapImages = AmapAroundSearchTools.drainMapImages();
            if (!mapImages.isEmpty()) {
                log.info("TravelAgent: {} map image(s) generated", mapImages.size());
            }
            List<byte[]> directionImages = AmapDirectionTools.drainMapImages();
            if (!directionImages.isEmpty()) {
                mapImages.addAll(directionImages);
            }

            // Clear DiDi taxi thread-local state after execution
            try {
                DiDiTaxiTools.clearCurrentUser();
            } catch (Exception ignored) {
                // not critical
            }

            log.info("TravelAgent response: {} chars", response != null ? response.length() : 0);

            // Detect PAUSED signal from LLM output (e.g. car selection)
            if (response != null && response.startsWith("__PAUSED__:")) {
                String messageToUser = response.substring("__PAUSED__:".length()).trim();

                UserMessageTool.PendingUserMessage pending = UserMessageTool.drain();
                String effectiveMessage = (pending != null && !pending.text().isBlank())
                        ? pending.text() : messageToUser;

                List<byte[]> allImages = new ArrayList<>();
                if (pending != null) {
                    allImages.addAll(pending.images());
                }
                allImages.addAll(BrowserTools.drainScreenshots());

                List<ModelReply.ImagePayload> imagePayloads = new ArrayList<>();
                for (int i = 0; i < allImages.size(); i++) {
                    imagePayloads.add(new ModelReply.ImagePayload(allImages.get(i),
                            "screenshot_" + (i + 1) + ".png"));
                }

                log.info("TravelAgent PAUSED: message={}, images={}", effectiveMessage, imagePayloads.size());
                ToolLoopContextRuntime.Report report = transcriptReportRef.get();
                return AgentResult.paused(task.taskId(), effectiveMessage,
                        report != null ? report.resumeState() : Map.of(), imagePayloads);
            }

            ToolLoopContextRuntime.Report report = transcriptReportRef.get();
            return AgentResult.success(task.taskId(), response, response,
                    report != null ? report.signals() : Map.of());
        } catch (RuntimeException e) {
            log.warn("TravelAgent tool loop failed: {}", e.getMessage());
            return AgentResult.failed(task.taskId(),
                    "出行服务查询失败：" + e.getMessage());
        }
    }

    private String buildEffectiveSystemPrompt(String userId, String instruction) {
        String prompt = SYSTEM_PROMPT;
        if (!skillsSummary.isEmpty()) {
            prompt += "\n\n" + skillsSummary;
        }
        return prompt;
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object val = params != null ? params.get(key) : null;
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
