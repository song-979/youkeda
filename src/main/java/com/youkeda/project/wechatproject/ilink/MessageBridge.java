package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * 消息桥接器——解决 ILinkClient 与 IlinkWechatService 之间的循环依赖。
 * <p>
 * ILinkClient 在构造时需要 OnMessageListener，而 IlinkWechatService 也依赖 ILinkClient。
 * MessageBridge 作为中间层：先创建空的 bridge 给 client，Service 就绪后再设置 delegate。
 */
public class MessageBridge implements OnMessageListener {

    private volatile Consumer<List<WeixinMessage>> delegate;

    public void setDelegate(Consumer<List<WeixinMessage>> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        Consumer<List<WeixinMessage>> d = delegate;
        if (d != null) {
            d.accept(messages);
        }
    }
}
