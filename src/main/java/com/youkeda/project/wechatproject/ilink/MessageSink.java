package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 消息输出端口——下游 agent 层实现此接口来接收微信消息。
 * <p>
 * 接入层收到微信消息后直接传递 SDK 的 {@link WeixinMessage}，
 * agent 可通过 {@link IlinkWechatService#getClient()} 下载媒体文件。
 */
@FunctionalInterface
public interface MessageSink {

    /**
     * 收到一条微信消息。
     * 实现者不应在此方法中执行长时间阻塞操作；耗时逻辑请异步处理。
     */
    void onMessage(WeixinMessage message);
}
