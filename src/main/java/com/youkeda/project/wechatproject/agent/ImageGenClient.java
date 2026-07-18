package com.youkeda.project.wechatproject.agent;

import java.io.IOException;

/**
 * 文生图调用抽象。
 * 实现类负责具体的 HTTP 协议通信，调用方仅依赖此接口。
 */
public interface ImageGenClient {

    /**
     * 根据文本描述生成图片。
     *
     * @param prompt 图片描述文本
     * @return 生成的图片字节数组
     * @throws IOException 网络或 API 调用失败时抛出
     */
    byte[] generate(String prompt) throws IOException;
}
