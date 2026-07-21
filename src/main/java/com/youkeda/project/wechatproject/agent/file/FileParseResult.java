package com.youkeda.project.wechatproject.agent.file;

import java.util.List;

/**
 * 文件解析结果。
 *
 * @param text     提取的文本内容
 * @param images   嵌入图片的原始字节（之后走 compressImage 压缩转 base64）
 * @param fileName 原始文件名
 */
public record FileParseResult(
        String text,
        List<byte[]> images,
        String fileName
) {
    public FileParseResult {
        if (images == null) {
            images = List.of();
        }
    }
}
