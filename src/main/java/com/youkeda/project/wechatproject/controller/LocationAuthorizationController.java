package com.youkeda.project.wechatproject.controller;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService;
import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService.AuthorizedLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/location")
public class LocationAuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(LocationAuthorizationController.class);

    private final LocationAuthorizationService locationAuthorizationService;
    private final ObjectProvider<ILinkClient> ilinkClientProvider;

    public LocationAuthorizationController(LocationAuthorizationService locationAuthorizationService,
                                           ObjectProvider<ILinkClient> ilinkClientProvider) {
        this.locationAuthorizationService = locationAuthorizationService;
        this.ilinkClientProvider = ilinkClientProvider;
    }

    @GetMapping(value = "/auth", produces = MediaType.TEXT_HTML_VALUE)
    public String authorizationPage(@RequestParam("token") String token) {
        if (!locationAuthorizationService.isTokenValid(token)) {
            return renderInfoPage("授权已失效", "这个定位授权链接已经失效，请回到聊天窗口重新获取新的链接。", false);
        }
        return renderAuthorizationPage(token);
    }

    @PostMapping(value = "/auth/complete", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LocationSubmitResponse completeAuthorization(@RequestBody LocationSubmitRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return new LocationSubmitResponse(false, "缺少授权令牌。", "", null, null);
        }
        if (request.longitude() == null || request.latitude() == null) {
            return new LocationSubmitResponse(false, "缺少经纬度，请重试。", "", null, null);
        }

        try {
            AuthorizedLocation location = locationAuthorizationService.completeAuthorization(
                    request.token(),
                    request.longitude(),
                    request.latitude(),
                    request.accuracyMeters());
            notifyWechatUser(location, false);
            return new LocationSubmitResponse(
                    true,
                    "当前位置已保存。如果位置有偏差，你还可以继续修正，系统会以最后一次保存的位置为准。",
                    location.bestDisplayAddress(),
                    location.longitude(),
                    location.latitude());
        } catch (Exception e) {
            log.warn("location authorization completion failed: {}", e.getMessage());
            return new LocationSubmitResponse(false, e.getMessage(), "", null, null);
        }
    }

    @PostMapping(value = "/auth/adjust", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LocationSubmitResponse adjustAuthorization(@RequestBody LocationAdjustRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return new LocationSubmitResponse(false, "缺少授权令牌。", "", null, null);
        }
        if (request.keyword() == null || request.keyword().isBlank()) {
            return new LocationSubmitResponse(false, "请填写要修正的位置名称或地址。", "", null, null);
        }

        try {
            AuthorizedLocation location = locationAuthorizationService.adjustAuthorizedLocation(
                    request.token(),
                    request.keyword());
            notifyWechatUser(location, true);
            return new LocationSubmitResponse(
                    true,
                    "位置已更新，系统会以这次修正后的结果为准。",
                    location.bestDisplayAddress(),
                    location.longitude(),
                    location.latitude());
        } catch (Exception e) {
            log.warn("location authorization adjustment failed: {}", e.getMessage());
            return new LocationSubmitResponse(false, e.getMessage(), "", null, null);
        }
    }

    private void notifyWechatUser(AuthorizedLocation location, boolean adjusted) {
        ILinkClient client = ilinkClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            String prefix = adjusted ? "已更新你的位置：" : "已收到你当前的位置：";
            client.sendText(location.userId(),
                    prefix + location.bestDisplayAddress()
                            + "。如果要继续打车，直接告诉我目的地，或继续说“打车去XX”。");
        } catch (Exception e) {
            log.warn("failed to notify user after location authorization: userId={}, error={}",
                    location.userId(), e.getMessage());
        }
    }

    private static String renderAuthorizationPage(String token) {
        String escapedToken = escapeHtml(token);
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                  <title>当前位置授权</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f5f7fb;
                      --panel: #ffffff;
                      --text: #1f2937;
                      --muted: #6b7280;
                      --primary: #0f766e;
                      --primary-strong: #115e59;
                      --border: #dbe3ef;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      background: linear-gradient(180deg, #eef6ff 0%%, var(--bg) 100%%);
                      color: var(--text);
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                    }
                    .shell {
                      width: min(420px, 100%%);
                      background: var(--panel);
                      border: 1px solid var(--border);
                      border-radius: 16px;
                      padding: 24px 20px;
                      box-shadow: 0 16px 40px rgba(15, 23, 42, 0.10);
                    }
                    h1 {
                      margin: 0 0 10px;
                      font-size: 22px;
                    }
                    p {
                      margin: 0;
                      color: var(--muted);
                      line-height: 1.6;
                    }
                    .actions, .adjust {
                      margin-top: 20px;
                      display: grid;
                      gap: 12px;
                    }
                    button {
                      border: 0;
                      border-radius: 12px;
                      padding: 14px 16px;
                      font-size: 16px;
                      font-weight: 600;
                      color: #fff;
                      background: var(--primary);
                    }
                    button.secondary {
                      background: #475569;
                    }
                    button:disabled {
                      opacity: 0.65;
                    }
                    .status {
                      margin-top: 18px;
                      padding: 14px 16px;
                      border-radius: 12px;
                      background: #f8fafc;
                      border: 1px solid var(--border);
                      white-space: pre-line;
                      line-height: 1.6;
                    }
                    .ok { color: var(--primary-strong); }
                    .error { color: #b91c1c; }
                    .hint {
                      margin-top: 14px;
                      font-size: 13px;
                    }
                    input {
                      width: 100%%;
                      border: 1px solid var(--border);
                      border-radius: 12px;
                      padding: 12px 14px;
                      font-size: 15px;
                    }
                  </style>
                </head>
                <body>
                  <main class="shell">
                    <h1>当前位置授权</h1>
                    <p>点击按钮授权读取你当前所在位置。保存后如果觉得不准，还可以继续修正，系统会以最后一次保存的位置为准。</p>
                    <div class="actions">
                      <button id="locateButton" type="button">获取当前位置</button>
                      <button id="retryButton" class="secondary" type="button" style="display:none;">重新定位</button>
                    </div>
                    <div id="status" class="status">等待授权…</div>
                    <div class="adjust" id="adjustPanel" style="display:none;">
                      <input id="addressInput" type="text" placeholder="如果位置有偏差，可输入附近地标或更准确的地址">
                      <button id="adjustButton" type="button">按地址修正位置</button>
                    </div>
                    <p class="hint">如果浏览器提示权限，请选择“允许”。修正成功后直接返回微信继续即可。</p>
                  </main>
                  <script>
                    const token = "%s";
                    const locateButton = document.getElementById("locateButton");
                    const retryButton = document.getElementById("retryButton");
                    const adjustButton = document.getElementById("adjustButton");
                    const addressInput = document.getElementById("addressInput");
                    const adjustPanel = document.getElementById("adjustPanel");
                    const statusEl = document.getElementById("status");

                    function setStatus(text, kind) {
                      statusEl.textContent = text;
                      statusEl.className = "status" + (kind ? " " + kind : "");
                    }

                    function showAdjustPanel() {
                      adjustPanel.style.display = "grid";
                      retryButton.style.display = "block";
                    }

                    async function submitJson(url, payload) {
                      const response = await fetch(url, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify(payload)
                      });
                      return response.json();
                    }

                    async function acquireLocation() {
                      if (!navigator.geolocation) {
                        setStatus("当前浏览器不支持定位，请换一个可定位的手机浏览器重试。", "error");
                        return;
                      }

                      locateButton.disabled = true;
                      retryButton.disabled = true;
                      adjustButton.disabled = true;
                      setStatus("正在获取当前位置，请稍候…");
                      navigator.geolocation.getCurrentPosition(async (position) => {
                        try {
                          const result = await submitJson("/location/auth/complete", {
                            token,
                            longitude: position.coords.longitude,
                            latitude: position.coords.latitude,
                            accuracyMeters: position.coords.accuracy
                          });
                          if (!result.success) {
                            setStatus(result.message || "提交失败，请稍后再试。", "error");
                            locateButton.disabled = false;
                            retryButton.disabled = false;
                            adjustButton.disabled = false;
                            return;
                          }
                          const address = result.address ? "\\n位置：" + result.address : "";
                          const coords = result.longitude != null && result.latitude != null
                            ? "\\n坐标：" + result.longitude + "," + result.latitude
                            : "";
                          setStatus((result.message || "当前位置已保存。") + address + coords + "\\n\\n如果位置不准，你可以继续修正。", "ok");
                          showAdjustPanel();
                        } catch (error) {
                          setStatus("提交定位失败，请稍后再试。", "error");
                        } finally {
                          locateButton.disabled = false;
                          retryButton.disabled = false;
                          adjustButton.disabled = false;
                        }
                      }, (error) => {
                        let message = "无法获取当前位置，请重试。";
                        if (error && error.code === 1) {
                          message = "你拒绝了定位权限，请重新点击并允许定位。";
                        } else if (error && error.code === 2) {
                          message = "定位暂时不可用，请检查手机定位服务后重试。";
                        } else if (error && error.code === 3) {
                          message = "定位超时，请在信号更好的地方再试一次。";
                        }
                        setStatus(message, "error");
                        locateButton.disabled = false;
                        retryButton.disabled = false;
                        adjustButton.disabled = false;
                      }, {
                        enableHighAccuracy: true,
                        timeout: 12000,
                        maximumAge: 0
                      });
                    }

                    locateButton.addEventListener("click", acquireLocation);
                    retryButton.addEventListener("click", acquireLocation);

                    adjustButton.addEventListener("click", async () => {
                      const keyword = addressInput.value.trim();
                      if (!keyword) {
                        setStatus("请先输入要修正的位置名称或地址。", "error");
                        return;
                      }
                      adjustButton.disabled = true;
                      locateButton.disabled = true;
                      retryButton.disabled = true;
                      setStatus("正在修正位置，请稍候…");
                      try {
                        const result = await submitJson("/location/auth/adjust", {
                          token,
                          keyword
                        });
                        if (!result.success) {
                          setStatus(result.message || "修正失败，请稍后再试。", "error");
                          return;
                        }
                        const address = result.address ? "\\n位置：" + result.address : "";
                        const coords = result.longitude != null && result.latitude != null
                          ? "\\n坐标：" + result.longitude + "," + result.latitude
                          : "";
                        setStatus((result.message || "位置已更新。") + address + coords + "\\n\\n现在可以返回微信继续。", "ok");
                      } catch (error) {
                        setStatus("修正位置失败，请稍后再试。", "error");
                      } finally {
                        adjustButton.disabled = false;
                        locateButton.disabled = false;
                        retryButton.disabled = false;
                      }
                    });
                  </script>
                </body>
                </html>
                """.formatted(escapedToken);
    }

    private static String renderInfoPage(String title, String message, boolean success) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                  <title>%s</title>
                  <style>
                    body {
                      margin: 0;
                      min-height: 100vh;
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                      background: #f5f7fb;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                    }
                    .card {
                      width: min(420px, 100%%);
                      padding: 24px 20px;
                      background: #fff;
                      border: 1px solid #dbe3ef;
                      border-radius: 16px;
                      box-shadow: 0 16px 40px rgba(15, 23, 42, 0.10);
                    }
                    h1 { margin: 0 0 12px; font-size: 22px; color: %s; }
                    p { margin: 0; line-height: 1.6; color: #475569; }
                  </style>
                </head>
                <body>
                  <main class="card">
                    <h1>%s</h1>
                    <p>%s</p>
                  </main>
                </body>
                </html>
                """.formatted(
                escapeHtml(title),
                success ? "#115e59" : "#b91c1c",
                escapeHtml(title),
                escapeHtml(message));
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public record LocationSubmitRequest(String token,
                                        Double longitude,
                                        Double latitude,
                                        Double accuracyMeters) {
    }

    public record LocationAdjustRequest(String token,
                                        String keyword) {
    }

    public record LocationSubmitResponse(boolean success,
                                         String message,
                                         String address,
                                         Double longitude,
                                         Double latitude) {
    }
}
