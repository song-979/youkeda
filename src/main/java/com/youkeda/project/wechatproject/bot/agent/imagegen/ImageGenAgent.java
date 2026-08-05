package com.youkeda.project.wechatproject.bot.agent.imagegen;

import com.youkeda.project.wechatproject.bot.agent.AgentCapability;
import com.youkeda.project.wechatproject.bot.agent.AgentResult;
import com.youkeda.project.wechatproject.bot.agent.AgentTask;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class ImageGenAgent implements AgentUnit {

    private static final Logger log = LoggerFactory.getLogger(ImageGenAgent.class);
    private final ImageGenClient imageGenClient;

    public ImageGenAgent(ImageGenClient imageGenClient) {
        this.imageGenClient = imageGenClient;
    }

    @Override
    public String getName() {
        return "IMAGE_GEN";
    }

    @Override
    public AgentCapability getCapability() {
        return new AgentCapability(
                "image-generation",
                "Generates images from descriptive prompts. Only for generating NEW static images, not GIFs or animated content.",
                List.of("text-to-image", "illustration", "visual design"),
                "image",
                List.of("生成图片", "画一张", "画个", "文生图", "帮我画", "生成一张"),
                true
        );
    }

    @Override
    public AgentResult execute(AgentTask task) throws IOException {
        String prompt = task.instruction();
        log.info("ImageGenAgent executing task: prompt={}", prompt);

        byte[] imageBytes = imageGenClient.generate(prompt);
        GeneratedImage generatedImage = GeneratedImage.normalize(imageBytes, "generated");

        log.info("ImageGenAgent generated {} bytes, normalized to {} ({})",
                imageBytes.length, generatedImage.fileName(), generatedImage.mediaType());
        return AgentResult.success(task.taskId(), generatedImage, "[image generated] prompt=" + prompt);
    }
}
