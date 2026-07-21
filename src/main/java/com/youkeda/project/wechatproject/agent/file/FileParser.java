package com.youkeda.project.wechatproject.agent.file;

import java.io.IOException;

/**
 * 文件解析器接口。每种文件类型对应一个实现。
 */
public interface FileParser {

    /**
     * 是否支持该文件扩展名。
     *
     * @param fileExtension 小写扩展名，如 "docx", "pdf", "txt"
     */
    boolean supports(String fileExtension);

    /**
     * 解析文件内容，提取文本和嵌入图片。
     *
     * @param bytes    文件原始字节
     * @param fileName 原始文件名（含扩展名）
     * @return 解析结果
     */
    FileParseResult parse(byte[] bytes, String fileName) throws IOException;
}
