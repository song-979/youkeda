package com.youkeda.project.wechatproject.bot.artifact;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Hard validation only; it does not judge the semantic quality of generated media. */
public class ArtifactValidator {

    public Validation validate(ArtifactWriteRequest request) {
        if (request == null || request.bytes() == null || request.bytes().length == 0) {
            return Validation.invalid("artifact payload is empty");
        }
        if (request.fileName() == null || request.fileName().isBlank()) {
            return Validation.invalid("artifact file name is empty");
        }
        if (request.type() == ArtifactType.IMAGE || request.type() == ArtifactType.SCREENSHOT
                || request.type() == ArtifactType.MAP) {
            try {
                if (ImageIO.read(new ByteArrayInputStream(request.bytes())) == null) {
                    return Validation.invalid("image payload cannot be decoded");
                }
            } catch (IOException e) {
                return Validation.invalid("image payload cannot be decoded");
            }
        }
        if (request.type() == ArtifactType.AUDIO && request.bytes().length < 32) {
            return Validation.invalid("audio payload is too small");
        }
        return Validation.accepted();
    }

    public record Validation(boolean valid, String message) {
        public static Validation accepted() { return new Validation(true, null); }
        public static Validation invalid(String message) { return new Validation(false, message); }
    }
}
