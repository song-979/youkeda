package com.youkeda.project.wechatproject.ilink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * iLink 客户端生命周期管理。
 * <p>
 * 在 Spring 容器就绪后自动发起登录；在容器销毁时关闭客户端。
 */
public class IlinkClientLifecycle implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IlinkClientLifecycle.class);

    private final IlinkWechatService service;
    private final IlinkProperties props;

    public IlinkClientLifecycle(IlinkWechatService service, IlinkProperties props) {
        this.service = service;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isAutoLogin()) {
            log.info("iLink auto-login disabled (ilink.auto-login=false)");
            return;
        }
        if (service.isLoggedIn()) {
            log.info("iLink already logged in (resumed from saved context)");
            return;
        }
        log.info("starting iLink login...");
        try {
            String qrcode = service.login();
            String qrPreview = qrcode != null && qrcode.length() > 80
                    ? qrcode.substring(0, 80) + "..." : qrcode;
            log.info("iLink QR code obtained ({} chars), preview: {}", qrcode != null ? qrcode.length() : 0, qrPreview);
            System.out.println("\n========================================");
            System.out.println("  请浏览器打开以下地址扫码登录微信:");
            System.out.println("  http://localhost:8080/ilink/qrcode");
            System.out.println("========================================\n");
        } catch (Exception e) {
            log.error("iLink auto-login failed on startup", e);
        }
    }

    @Override
    public void destroy() {
        service.close();
    }
}
