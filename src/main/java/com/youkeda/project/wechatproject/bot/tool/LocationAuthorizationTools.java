package com.youkeda.project.wechatproject.bot.tool;

import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService;
import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService.AuthorizedLocation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Component
public class LocationAuthorizationTools implements ToolService.ProjectTool {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final LocationAuthorizationService locationAuthorizationService;

    public LocationAuthorizationTools(LocationAuthorizationService locationAuthorizationService) {
        this.locationAuthorizationService = locationAuthorizationService;
    }

    public static void setCurrentUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT_USER.set(userId);
        }
    }

    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    @Override
    public String category() {
        return "map_navigation";
    }

    @Tool(name = "request_phone_location_authorization",
            description = "Generate a short-lived location authorization link for the current user. "
                    + "Use this when the user needs their current location for taxi, nearby search, or navigation, "
                    + "but has not provided a usable current address or coordinate. "
                    + "After calling this tool, ask the user to open the link on their phone and complete authorization. "
                    + "Do not continue with taxi price estimation until the user confirms authorization or you successfully read the latest authorized location.")
    public String requestPhoneLocationAuthorization() {
        String userId = CURRENT_USER.get();
        if (userId == null || userId.isBlank()) {
            return "无法发起定位授权：当前用户信息不可用，请稍后再试。";
        }

        try {
            String url = locationAuthorizationService.createAuthorizationUrl(userId);
            return """
                    请让用户在手机上打开下面的定位授权页，并允许浏览器获取当前位置：

                    %s

                    授权完成后，系统会自动收到用户当前位置。接下来可以继续打车、导航或周边搜索。
                    """.formatted(url).trim();
        } catch (Exception e) {
            return "发起定位授权失败：" + e.getMessage();
        }
    }

    @Tool(name = "get_latest_authorized_phone_location",
            description = "Read the latest authorized phone location for the current user. "
                    + "Use this after the user says they have completed location authorization, "
                    + "or before taxi estimation when a recent authorized location may already exist. "
                    + "Returns longitude, latitude, address, accuracy, and authorization time.")
    public String getLatestAuthorizedPhoneLocation() {
        String userId = CURRENT_USER.get();
        if (userId == null || userId.isBlank()) {
            return "读取定位失败：当前用户信息不可用，请稍后再试。";
        }

        Optional<AuthorizedLocation> location = locationAuthorizationService.getLatestLocation(userId);
        if (location.isEmpty()) {
            return "未找到近期授权定位，请先让用户打开定位授权链接完成授权。";
        }

        AuthorizedLocation value = location.get();
        StringBuilder sb = new StringBuilder();
        sb.append("已读取最近一次手机授权定位：\n");
        sb.append("地址：").append(blankToUnknown(value.bestDisplayAddress())).append("\n");
        sb.append("坐标：").append(value.longitude()).append(",").append(value.latitude()).append("\n");
        sb.append("经度：").append(value.longitude()).append("\n");
        sb.append("纬度：").append(value.latitude()).append("\n");
        if (value.accuracyMeters() != null) {
            sb.append("定位精度：").append(Math.round(value.accuracyMeters())).append("米\n");
        }
        sb.append("授权时间：").append(DISPLAY_TIME.format(value.authorizedAt())).append("\n");
        sb.append("距现在：").append(formatElapsed(Duration.between(value.authorizedAt(), Instant.now()))).append("\n");
        if (value.adcode() != null && !value.adcode().isBlank()) {
            sb.append("adcode：").append(value.adcode()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private static String formatElapsed(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "分钟";
        }
        long hours = minutes / 60;
        long remainMinutes = minutes % 60;
        if (remainMinutes == 0) {
            return hours + "小时";
        }
        return hours + "小时" + remainMinutes + "分钟";
    }
}
