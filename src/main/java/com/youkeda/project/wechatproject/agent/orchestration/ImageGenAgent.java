package com.youkeda.project.wechatproject.agent.orchestration;

import com.youkeda.project.wechatproject.agent.GeneratedImage;
import com.youkeda.project.wechatproject.agent.ImageGenClient;
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
                "Generates images from descriptive prompts.",
                List.of("text-to-image", "illustration", "visual design"),
                "image"
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
        return AgentResult.success(
                task.taskId(),
                generatedImage,
                "[image generated] prompt=" + prompt
        );
    }
}
