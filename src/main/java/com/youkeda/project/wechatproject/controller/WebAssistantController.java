package com.youkeda.project.wechatproject.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.GeneratedImage;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.DocumentService.ParseResult;
import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService;
import com.youkeda.project.wechatproject.bot.service.LocationAuthorizationService.ReverseGeocodeInfo;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TextToSpeechClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TtsResult;
import com.youkeda.project.wechatproject.bot.tool.AmapAroundSearchTools;
import com.youkeda.project.wechatproject.bot.tool.AmapDirectionTools;
import com.youkeda.project.wechatproject.bot.tool.AutomationRuntime;
import com.youkeda.project.wechatproject.bot.tool.DiDiTaxiTools;
import com.youkeda.project.wechatproject.bot.tool.IpInfoTool;
import com.youkeda.project.wechatproject.bot.tool.WeatherTools;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/web-assistant")
public class WebAssistantController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PUBLIC_IP_URL = "https://api64.ipify.org";
    private static final String AMAP_PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp"
    );
    private static final int MAX_FILE_TEXT_CHARS = 2400;
    private static final int MAX_IMAGE_COUNT = 8;
    private static final Pattern APP_LINK_PATTERN = Pattern.compile("(https?://\\S+|diditaxi://\\S+)");
    private static final String WEATHER_LOCATION_PROMPT = """
            你是天气地点路由助手。
            用户想查询天气，请把输入里的地点标准化成最适合天气接口查询的单个值。
            规则：
            1. 只输出一个值，不要解释，不要多行。
            2. 优先输出中国大陆 6 位 adcode。
            3. 如果无法确定 adcode，就输出最精确的中文地点名，只保留地点本身。
            4. 去掉“今天、明天、天气、预报、查一下、帮我看”等无关词。
            5. 不要输出 JSON，不要输出多个候选。
            """;
    private static final String IMAGE_EDIT_PROMPT = """
            你是图片工厂的改图提示词助手。
            请结合用户上传的参考图片和修改要求，输出一段可直接用于图片生成的中文提示词。
            规则：
            1. 只输出最终提示词，不要解释。
            2. 保留用户明确要求保留的主体、风格或构图信息。
            3. 如果用户没有说清楚，就优先保留主体识别度和自然质感。
            4. 提示词里补足材质、光线、镜头、背景、构图和细节。
            """;

    private final IpInfoTool ipInfoTool;
    private final WeatherTools weatherTools;
    private final LocationAuthorizationService locationAuthorizationService;
    private final AiModelClient aiModelClient;
    private final DocumentService documentService;
    private final ObjectProvider<ImageGenClient> imageGenClientProvider;
    private final ObjectProvider<TextToSpeechClient> textToSpeechClientProvider;
    private final ObjectProvider<DiDiTaxiTools> diDiTaxiToolsProvider;
    private final ObjectProvider<AmapAroundSearchTools> amapAroundSearchToolsProvider;
    private final ObjectProvider<AmapDirectionTools> amapDirectionToolsProvider;
    private final ObjectProvider<AutomationRuntime> automationRuntimeProvider;
    private final RestTemplate restTemplate;
    private final String amapKey;
    private final String amapPrivateKey;

    public WebAssistantController(IpInfoTool ipInfoTool,
                                  WeatherTools weatherTools,
                                  LocationAuthorizationService locationAuthorizationService,
                                  AiModelClient aiModelClient,
                                  DocumentService documentService,
                                  ObjectProvider<ImageGenClient> imageGenClientProvider,
                                  ObjectProvider<TextToSpeechClient> textToSpeechClientProvider,
                                  ObjectProvider<DiDiTaxiTools> diDiTaxiToolsProvider,
                                  ObjectProvider<AmapAroundSearchTools> amapAroundSearchToolsProvider,
                                  ObjectProvider<AmapDirectionTools> amapDirectionToolsProvider,
                                  ObjectProvider<AutomationRuntime> automationRuntimeProvider,
                                  @Value("${agent.tools.weather.amap-key:}") String amapKey,
                                  @Value("${agent.tools.weather.amap-private-key:}") String amapPrivateKey) {
        this.ipInfoTool = ipInfoTool;
        this.weatherTools = weatherTools;
        this.locationAuthorizationService = locationAuthorizationService;
        this.aiModelClient = aiModelClient;
        this.documentService = documentService;
        this.imageGenClientProvider = imageGenClientProvider;
        this.textToSpeechClientProvider = textToSpeechClientProvider;
        this.diDiTaxiToolsProvider = diDiTaxiToolsProvider;
        this.amapAroundSearchToolsProvider = amapAroundSearchToolsProvider;
        this.amapDirectionToolsProvider = amapDirectionToolsProvider;
        this.automationRuntimeProvider = automationRuntimeProvider;
        this.restTemplate = createRestTemplate();
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.amapPrivateKey = amapPrivateKey == null ? "" : amapPrivateKey.trim();
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public StatusResponse status() {
        return new StatusResponse(
                true,
                "网页助手接口在线",
                "已接入文本、多图、多文件，图片工厂与语音输出按当前配置启用。",
                true,
                imageGenClientProvider.getIfAvailable() != null,
                textToSpeechClientProvider.getIfAvailable() != null,
                documentService != null,
                diDiTaxiToolsProvider.getIfAvailable() != null,
                automationRuntimeProvider.getIfAvailable() != null,
                amapAroundSearchToolsProvider.getIfAvailable() != null,
                amapDirectionToolsProvider.getIfAvailable() != null
        );
    }

    @GetMapping(value = "/ip", produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse ip(@RequestParam(value = "target", required = false) String target,
                           HttpServletRequest request) {
        String resolvedTarget = target == null || target.isBlank() ? resolvePublicIp(request) : target.trim();
        String content = ipInfoTool.lookupIpInfo(resolvedTarget);
        String summary = (target == null || target.isBlank())
                ? "已查询当前设备出口公网 IP 的信息。"
                : "已查询指定 IP 或域名的信息。";
        return new ToolResponse(true, "ip", "IP 查询结果", summary, content);
    }

    @GetMapping(value = "/weather/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse currentWeather(@RequestParam("location") String location) {
        String normalizedLocation = normalizeWeatherLocation(location);
        String content = weatherTools.getCurrentWeather(normalizedLocation);
        return new ToolResponse(true, "weather-current", "当前天气",
                "已按地点返回实时天气信息。", content);
    }

    @GetMapping(value = "/weather/forecast", produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse weatherForecast(@RequestParam("location") String location) {
        String normalizedLocation = normalizeWeatherLocation(location);
        String content = weatherTools.getWeatherForecast(normalizedLocation);
        return new ToolResponse(true, "weather-forecast", "天气预报",
                "已返回未来几天的天气概览。", content);
    }

    @GetMapping(value = "/location/reverse", produces = MediaType.APPLICATION_JSON_VALUE)
    public LocationResponse reverseLocation(@RequestParam("longitude") double longitude,
                                            @RequestParam("latitude") double latitude) {
        ReverseGeocodeInfo info = locationAuthorizationService.reverseGeocodeInfo(longitude, latitude);
        return new LocationResponse(
                true,
                "location",
                "当前位置",
                "已根据浏览器定位反查地址。",
                info.bestDisplayAddress(),
                info.longitude(),
                info.latitude(),
                info.adcode(),
                info.city(),
                info.province()
        );
    }

    @PostMapping(value = "/taxi/link", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaxiLinkResponse generateTaxiLink(@RequestBody TaxiLinkRequest request) {
        if (request == null) {
            return new TaxiLinkResponse(false, "打车请求不能为空。", "", "", "", "", 0, 0, 0, 0);
        }
        if (request.originLongitude() == null || request.originLatitude() == null) {
            return new TaxiLinkResponse(false, "缺少出发地坐标，请先完成定位。", "", "", "", "", 0, 0, 0, 0);
        }
        if (request.destination() == null || request.destination().isBlank()) {
            return new TaxiLinkResponse(false, "请输入目的地。", "", "", "", "", 0, 0, 0, 0);
        }

        DiDiTaxiTools didiTaxiTools = diDiTaxiToolsProvider.getIfAvailable();
        if (didiTaxiTools == null) {
            return new TaxiLinkResponse(false, "当前环境未启用打车工具。", "", "", "", "", 0, 0, 0, 0);
        }
        if (amapKey.isBlank()) {
            return new TaxiLinkResponse(false, "当前环境未配置地图检索能力，暂时无法解析目的地。", "", "", "", "", 0, 0, 0, 0);
        }

        try {
            ReverseGeocodeInfo originInfo = locationAuthorizationService.reverseGeocodeInfo(
                    request.originLongitude(),
                    request.originLatitude());
            PlaceInfo destination = searchPlace(request.destination().trim(), originInfo.city(), originInfo.adcode());
            String raw = didiTaxiTools.taxiGenerateRideAppLink(
                    stripTrailingZeros(request.originLongitude()),
                    stripTrailingZeros(request.originLatitude()),
                    stripTrailingZeros(destination.longitude()),
                    stripTrailingZeros(destination.latitude()),
                    ""
            );
            String appLink = extractAppLink(raw);
            return new TaxiLinkResponse(
                    true,
                    "已生成滴滴行程链接。",
                    defaultIfBlank(originInfo.bestDisplayAddress(), request.originName()),
                    destination.displayName(),
                    raw,
                    defaultIfBlank(appLink, ""),
                    request.originLongitude(),
                    request.originLatitude(),
                    destination.longitude(),
                    destination.latitude()
            );
        } catch (Exception e) {
            return new TaxiLinkResponse(false, e.getMessage(), "", "", "", "", 0, 0, 0, 0);
        }
    }

    @PostMapping(value = "/around/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse searchAround(@RequestBody AroundSearchRequest request) {
        if (request == null || request.longitude() == null || request.latitude() == null) {
            return new ToolResponse(false, "around", "周边搜索失败", "缺少中心点坐标。", "");
        }
        if (request.keywords() == null || request.keywords().isBlank()) {
            return new ToolResponse(false, "around", "周边搜索失败", "请输入要搜索的周边内容。", "");
        }

        AmapAroundSearchTools aroundTools = amapAroundSearchToolsProvider.getIfAvailable();
        if (aroundTools == null) {
            return new ToolResponse(false, "around", "周边搜索失败", "当前环境未启用周边搜索工具。", "");
        }

        String location = stripTrailingZeros(request.longitude()) + "," + stripTrailingZeros(request.latitude());
        String content = aroundTools.searchPlacesAround(
                location,
                request.keywords().trim(),
                "",
                request.radius(),
                request.limit()
        );
        return new ToolResponse(true, "around", "周边搜索结果", "已返回当前位置周边结果。", content);
    }

    @PostMapping(value = "/route/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse searchRoute(@RequestBody RouteSearchRequest request) {
        if (request == null || request.originLongitude() == null || request.originLatitude() == null) {
            return new ToolResponse(false, "route", "路线规划失败", "缺少起点坐标，请先定位。", "");
        }
        if (request.destination() == null || request.destination().isBlank()) {
            return new ToolResponse(false, "route", "路线规划失败", "请输入目的地。", "");
        }

        AmapDirectionTools directionTools = amapDirectionToolsProvider.getIfAvailable();
        if (directionTools == null) {
            return new ToolResponse(false, "route", "路线规划失败", "当前环境未启用路线规划工具。", "");
        }

        try {
            ReverseGeocodeInfo originInfo = locationAuthorizationService.reverseGeocodeInfo(
                    request.originLongitude(),
                    request.originLatitude());
            PlaceInfo destination = searchPlace(request.destination().trim(), originInfo.city(), originInfo.adcode());
            String origin = stripTrailingZeros(request.originLongitude()) + "," + stripTrailingZeros(request.originLatitude());
            String dest = stripTrailingZeros(destination.longitude()) + "," + stripTrailingZeros(destination.latitude());
            String mode = defaultIfBlank(request.mode(), "driving").toLowerCase(Locale.ROOT);
            String content = switch (mode) {
                case "walking" -> directionTools.searchWalking(origin, dest);
                case "transit" -> directionTools.searchTransit(origin, dest, defaultIfBlank(originInfo.city(), originInfo.adcode()));
                case "bicycling" -> directionTools.searchBicycling(origin, dest);
                default -> directionTools.searchDriving(origin, dest, 0);
            };
            return new ToolResponse(true, "route", "路线规划结果",
                    "已生成从当前位置到目的地的路线方案。", content);
        } catch (Exception e) {
            return new ToolResponse(false, "route", "路线规划失败", e.getMessage(), "");
        }
    }

    @PostMapping(value = "/automation/reminder", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AutomationResponse createReminder(@RequestBody ReminderRequest request) {
        AutomationRuntime runtime = automationRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return new AutomationResponse(false, "reminder", "定时提醒不可用", "当前环境未启用提醒能力。", "", List.of());
        }
        if (request == null || request.title() == null || request.title().isBlank() || request.remindAt() == null || request.remindAt().isBlank()) {
            return new AutomationResponse(false, "reminder", "定时提醒失败", "标题和提醒时间不能为空。", "", List.of());
        }

        AutomationRuntime.ReminderResult result = runtime.createReminder(
                request.title().trim(),
                request.remindAt().trim(),
                defaultIfBlank(request.message(), request.title().trim())
        );
        if (!result.success()) {
            return new AutomationResponse(false, "reminder", "定时提醒失败", result.message(), "", List.of());
        }
        var reminder = result.reminder();
        String text = "提醒已创建\nID: " + reminder.id()
                + "\n标题: " + reminder.title()
                + "\n时间: " + reminder.remindAt();
        return new AutomationResponse(true, "reminder", "定时提醒已创建", result.message(), text, List.of(reminder.id()));
    }

    @GetMapping(value = "/automation/reminders", produces = MediaType.APPLICATION_JSON_VALUE)
    public AutomationResponse listReminders() {
        AutomationRuntime runtime = automationRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return new AutomationResponse(false, "reminder", "提醒列表不可用", "当前环境未启用提醒能力。", "", List.of());
        }
        var reminders = runtime.listReminders(null);
        if (reminders.isEmpty()) {
            return new AutomationResponse(true, "reminder", "提醒列表", "当前没有提醒。", "当前没有提醒。", List.of());
        }
        StringBuilder content = new StringBuilder("提醒列表：\n");
        List<String> ids = new ArrayList<>();
        reminders.forEach(reminder -> {
            ids.add(reminder.id());
            content.append("- ").append(reminder.id())
                    .append(" [").append(reminder.status()).append("] ")
                    .append(reminder.title())
                    .append(" @ ").append(reminder.remindAt())
                    .append("\n");
        });
        return new AutomationResponse(true, "reminder", "提醒列表", "已返回当前提醒。", content.toString().trim(), ids);
    }

    @PostMapping(value = "/automation/schedule", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AutomationResponse createSchedule(@RequestBody ScheduleRequest request) {
        AutomationRuntime runtime = automationRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return new AutomationResponse(false, "schedule", "日程不可用", "当前环境未启用日程能力。", "", List.of());
        }
        if (request == null
                || request.title() == null || request.title().isBlank()
                || request.startAt() == null || request.startAt().isBlank()
                || request.endAt() == null || request.endAt().isBlank()) {
            return new AutomationResponse(false, "schedule", "创建日程失败", "标题、开始时间、结束时间不能为空。", "", List.of());
        }

        AutomationRuntime.ScheduleResult result = runtime.createScheduleItem(
                request.title().trim(),
                request.startAt().trim(),
                request.endAt().trim(),
                defaultIfBlank(request.notes(), "")
        );
        if (!result.success()) {
            return new AutomationResponse(false, "schedule", "创建日程失败", result.message(), "", List.of());
        }
        var item = result.item();
        String text = "日程已创建\nID: " + item.id()
                + "\n标题: " + item.title()
                + "\n开始: " + item.startAt()
                + "\n结束: " + item.endAt();
        return new AutomationResponse(true, "schedule", "日程已创建", result.message(), text, List.of(item.id()));
    }

    @GetMapping(value = "/automation/schedules", produces = MediaType.APPLICATION_JSON_VALUE)
    public AutomationResponse listSchedules(@RequestParam("from") String from,
                                            @RequestParam("to") String to) {
        AutomationRuntime runtime = automationRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return new AutomationResponse(false, "schedule", "鏃ョ▼鍒楄〃涓嶅彲鐢?", "褰撳墠鐜鏈惎鐢ㄦ棩绋嬭兘鍔涖€?", "", List.of());
        }
        var items = runtime.listScheduleItems(from, to, null);
        if (items.isEmpty()) {
            return new AutomationResponse(true, "schedule", "鏃ョ▼鍒楄〃", "褰撳墠鏃堕棿鑼冨洿鍐呮病鏈夋棩绋嬨€?", "褰撳墠鏃堕棿鑼冨洿鍐呮病鏈夋棩绋嬨€?", List.of());
        }
        StringBuilder content = new StringBuilder("鏃ョ▼鍒楄〃锛歕n");
        List<String> ids = new ArrayList<>();
        items.forEach(item -> {
            ids.add(item.id());
            content.append("- ").append(item.id())
                    .append(" [").append(item.status()).append("] ")
                    .append(item.title())
                    .append(" @ ").append(item.startAt())
                    .append(" - ").append(item.endAt())
                    .append("\n");
        });
        return new AutomationResponse(true, "schedule", "鏃ョ▼鍒楄〃", "宸茶繑鍥炲綋鍓嶆棩绋嬨€?", content.toString().trim(), ids);
    }

    @PostMapping(value = "/text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AssistantResponse textAssistant(
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "voiceOutput", defaultValue = "false") boolean voiceOutput,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        PreparedUpload prepared = prepareUpload(images, files);
        String userPrompt = defaultIfBlank(prompt, prepared.hasVisualInputs()
                ? "请结合我上传的图片和文件内容，直接给出结果。"
                : "请帮我处理这个请求。");
        String finalPrompt = composeTextPrompt(userPrompt, prepared.fileInfos());
        String answer = aiModelClient.chat(finalPrompt, prepared.imageDataUrls(), List.of());

        AudioAsset audio = null;
        List<String> notices = new ArrayList<>(prepared.notices());
        if (voiceOutput) {
            TextToSpeechClient ttsClient = textToSpeechClientProvider.getIfAvailable();
            if (ttsClient == null) {
                notices.add("当前环境未启用语音输出，已返回文字结果。");
            } else {
                TtsResult ttsResult = ttsClient.synthesize(answer);
                audio = toAudioAsset(ttsResult);
            }
        }

        return new AssistantResponse(
                true,
                "text",
                "文本大模型",
                "已结合文字、图片和文件内容返回结果。",
                answer,
                List.of(),
                audio,
                prepared.fileInfos(),
                notices
        );
    }

    @PostMapping(value = "/image/factory", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AssistantResponse imageFactory(
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        PreparedUpload prepared = prepareUpload(images, files);
        boolean hasReferenceImage = prepared.hasVisualInputs();
        String instruction = defaultIfBlank(prompt, hasReferenceImage
                ? "请在保留主体识别度的前提下优化这张图，画面更自然清晰。"
                : "请生成一张画面完整、细节清晰的图片。");

        ImageGenClient imageGenClient = imageGenClientProvider.getIfAvailable();
        List<String> notices = new ArrayList<>(prepared.notices());
        String optimizedPrompt = buildImagePrompt(instruction, prepared, hasReferenceImage, notices);

        if (imageGenClient == null) {
            notices.add("当前环境未启用图片生成接口，已返回整理后的提示词。");
            return new AssistantResponse(
                    false,
                    "image",
                    "图片工厂",
                    hasReferenceImage ? "已根据参考图整理出重绘提示词。" : "当前只能先整理提示词。",
                    optimizedPrompt,
                    List.of(),
                    null,
                    prepared.fileInfos(),
                    notices
            );
        }

        try {
            byte[] rawBytes = imageGenClient.generate(optimizedPrompt);
            GeneratedImage generated = GeneratedImage.normalize(rawBytes, hasReferenceImage ? "edited-image" : "generated-image");
            String text = hasReferenceImage ? "已根据参考图和修改要求生成新图片。" : "已根据提示词生成图片。";
            return new AssistantResponse(
                    true,
                    "image",
                    "图片工厂",
                    hasReferenceImage ? "已按参考图重绘修改版。" : "文生图已完成。",
                    text,
                    List.of(new ImageAsset(generated.fileName(), generated.mediaType(), generated.dataUrl(), hasReferenceImage ? "edited" : "generated")),
                    null,
                    prepared.fileInfos(),
                    notices
            );
        } catch (IOException e) {
            notices.add("图片接口本次返回失败，已保留可重试的提示词。");
            return new AssistantResponse(
                    false,
                    "image",
                    "图片工厂",
                    "图片生成暂时失败。",
                    optimizedPrompt,
                    List.of(),
                    null,
                    prepared.fileInfos(),
                    notices
            );
        }
    }

    private PreparedUpload prepareUpload(List<MultipartFile> images, List<MultipartFile> files) throws IOException {
        List<String> imageDataUrls = new ArrayList<>();
        List<FileInsight> fileInfos = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        appendImageParts(images, imageDataUrls, fileInfos, notices);
        appendFileParts(files, imageDataUrls, fileInfos, notices);

        if (imageDataUrls.size() > MAX_IMAGE_COUNT) {
            notices.add("图片较多，已自动截取前 " + MAX_IMAGE_COUNT + " 张参与处理。");
            imageDataUrls = new ArrayList<>(imageDataUrls.subList(0, MAX_IMAGE_COUNT));
        }

        return new PreparedUpload(imageDataUrls, fileInfos, notices);
    }

    private void appendImageParts(List<MultipartFile> images,
                                  List<String> imageDataUrls,
                                  List<FileInsight> fileInfos,
                                  List<String> notices) throws IOException {
        if (images == null) {
            return;
        }
        for (MultipartFile file : images) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String fileName = safeFileName(file.getOriginalFilename(), "image");
            try {
                GeneratedImage normalized = GeneratedImage.normalize(file.getBytes(), fileName);
                imageDataUrls.add(normalized.dataUrl());
                fileInfos.add(new FileInsight(fileName, "图片", "", 1));
            } catch (IOException e) {
                notices.add(fileName + " 读取失败，已跳过。");
            }
        }
    }

    private void appendFileParts(List<MultipartFile> files,
                                 List<String> imageDataUrls,
                                 List<FileInsight> fileInfos,
                                 List<String> notices) throws IOException {
        if (files == null) {
            return;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String fileName = safeFileName(file.getOriginalFilename(), "attachment");
            String extension = DocumentService.extractExtension(fileName);

            if (isImageFile(file.getContentType(), extension)) {
                try {
                    GeneratedImage normalized = GeneratedImage.normalize(file.getBytes(), fileName);
                    imageDataUrls.add(normalized.dataUrl());
                    fileInfos.add(new FileInsight(fileName, "图片文件", "", 1));
                } catch (IOException e) {
                    notices.add(fileName + " 读取失败，已跳过。");
                }
                continue;
            }

            try {
                ParseResult result = documentService.parse(file.getBytes(), fileName);
                String extractedText = truncate(result.text(), MAX_FILE_TEXT_CHARS);
                int extractedImages = 0;
                for (byte[] imageBytes : result.images()) {
                    if (imageBytes == null || imageBytes.length == 0) {
                        continue;
                    }
                    GeneratedImage normalized = GeneratedImage.normalize(imageBytes, fileName + "-image");
                    imageDataUrls.add(normalized.dataUrl());
                    extractedImages++;
                }
                fileInfos.add(new FileInsight(fileName, "文件", extractedText, extractedImages));
            } catch (IOException e) {
                notices.add(fileName + " 暂不支持解析，已跳过文件内容。");
                fileInfos.add(new FileInsight(fileName, "文件", "", 0));
            }
        }
    }

    private String composeTextPrompt(String prompt, List<FileInsight> fileInfos) {
        StringBuilder builder = new StringBuilder(prompt.trim());
        List<String> excerpts = new ArrayList<>();
        for (FileInsight fileInfo : fileInfos) {
            if (fileInfo.extractedText() == null || fileInfo.extractedText().isBlank()) {
                continue;
            }
            excerpts.add("文件《" + fileInfo.fileName() + "》内容摘录：\n" + fileInfo.extractedText());
        }
        if (!excerpts.isEmpty()) {
            builder.append("\n\n补充上下文：\n");
            builder.append(String.join("\n\n", excerpts));
        }
        return builder.toString();
    }

    private String buildImagePrompt(String instruction,
                                    PreparedUpload prepared,
                                    boolean hasReferenceImage,
                                    List<String> notices) {
        String combined = composeTextPrompt(instruction, prepared.fileInfos());
        if (!hasReferenceImage) {
            return combined;
        }
        try {
            String result = aiModelClient.chat(
                    IMAGE_EDIT_PROMPT + "\n\n用户要求：" + combined,
                    prepared.imageDataUrls(),
                    List.of()
            );
            if (result == null || result.isBlank()) {
                notices.add("参考图提示词整理失败，已使用原始要求重试。");
                return combined;
            }
            return result.trim();
        } catch (IOException e) {
            notices.add("参考图提示词整理失败，已使用原始要求重试。");
            return combined;
        }
    }

    private AudioAsset toAudioAsset(TtsResult ttsResult) {
        if (ttsResult == null || ttsResult.audioBytes() == null || ttsResult.audioBytes().length == 0) {
            return null;
        }
        String format = defaultIfBlank(ttsResult.format(), "wav").toLowerCase(Locale.ROOT);
        String mediaType = switch (format) {
            case "mp3" -> "audio/mpeg";
            case "ogg" -> "audio/ogg";
            default -> "audio/wav";
        };
        String dataUrl = "data:" + mediaType + ";base64,"
                + Base64.getEncoder().encodeToString(ttsResult.audioBytes());
        return new AudioAsset("speech." + format, mediaType, dataUrl);
    }

    private String resolvePublicIp(HttpServletRequest request) {
        String url = UriComponentsBuilder.fromUriString(PUBLIC_IP_URL)
                .queryParam("format", "json")
                .build()
                .encode()
                .toUriString();
        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode ipNode = root.path("ip");
            if (ipNode.isTextual() && !ipNode.asText().isBlank()) {
                return ipNode.asText();
            }
        } catch (Exception ignored) {
        }
        return request != null ? request.getRemoteAddr() : "";
    }

    private String normalizeWeatherLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String trimmed = location.trim();
        if (trimmed.matches("\\d{6}")) {
            return trimmed;
        }
        try {
            String result = aiModelClient.chat(
                    WEATHER_LOCATION_PROMPT + "\n\n用户输入：" + trimmed,
                    List.of(),
                    List.of()
            );
            if (result == null || result.isBlank()) {
                return trimmed;
            }
            String normalized = result.trim().replace("\r", "").replace("\n", "");
            return normalized.isBlank() ? trimmed : normalized;
        } catch (IOException e) {
            return trimmed;
        }
    }

    private PlaceInfo searchPlace(String keyword, String city, String adcode) {
        try {
            String cityHint = defaultIfBlank(adcode, city);
            var builder = UriComponentsBuilder.fromUriString(AMAP_PLACE_TEXT_URL)
                    .queryParam("key", amapKey)
                    .queryParam("keywords", keyword)
                    .queryParam("offset", 1)
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .queryParam("output", "JSON");
            if (cityHint != null && !cityHint.isBlank()) {
                builder.queryParam("city", cityHint);
                builder.queryParam("citylimit", true);
            }

            String url = appendAmapSign(builder).build().encode().toUriString();
            JsonNode root = OBJECT_MAPPER.readTree(restTemplate.getForObject(url, String.class));
            if (!"1".equals(root.path("status").asText())) {
                throw new IllegalStateException("目的地检索失败：" + root.path("info").asText("地图服务异常"));
            }
            JsonNode poi = root.path("pois").path(0);
            String location = poi.path("location").asText("");
            if (location.isBlank() || !location.contains(",")) {
                throw new IllegalStateException("没有找到可用的目的地坐标。");
            }
            String[] parts = location.split(",", 2);
            String name = poi.path("name").asText(keyword);
            String address = poi.path("address").asText("");
            String displayName = address == null || address.isBlank() ? name : name + " " + address;
            return new PlaceInfo(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    displayName
            );
        } catch (Exception e) {
            throw new IllegalStateException("目的地解析失败：" + e.getMessage(), e);
        }
    }

    private UriComponentsBuilder appendAmapSign(UriComponentsBuilder builder) {
        if (amapPrivateKey == null || amapPrivateKey.isBlank()) {
            return builder;
        }
        Map<String, String> params = new TreeMap<>();
        builder.build().getQueryParams().toSingleValueMap().forEach((key, value) -> {
            if (!"sig".equals(key)) {
                params.put(key, value == null ? "" : value);
            }
        });
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!raw.isEmpty()) {
                raw.append('&');
            }
            raw.append(entry.getKey()).append('=').append(entry.getValue());
        }
        raw.append(amapPrivateKey);
        return builder.queryParam("sig", md5(raw.toString()));
    }

    private String extractAppLink(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = APP_LINK_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String stripTrailingZeros(Double value) {
        if (value == null) {
            return "";
        }
        String text = Double.toString(value);
        if (!text.contains(".")) {
            return text;
        }
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private boolean isImageFile(String contentType, String extension) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        return extension != null && IMAGE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static String safeFileName(String fileName, String fallback) {
        return (fileName == null || fileName.isBlank()) ? fallback : fileName.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n\n[内容已截断]";
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    public record StatusResponse(boolean success,
                                 String title,
                                 String summary,
                                 boolean chatAvailable,
                                 boolean imageGenerationAvailable,
                                 boolean voiceAvailable,
                                 boolean fileParsingAvailable,
                                 boolean taxiAvailable,
                                 boolean reminderAvailable,
                                 boolean aroundSearchAvailable,
                                 boolean routeAvailable) {
    }

    public record ToolResponse(boolean success,
                               String type,
                               String title,
                               String summary,
                               String content) {
    }

    public record LocationResponse(boolean success,
                                   String type,
                                   String title,
                                   String summary,
                                   String address,
                                   double longitude,
                                   double latitude,
                                   String adcode,
                                   String city,
                                   String province) {
    }

    public record AssistantResponse(boolean success,
                                    String mode,
                                    String title,
                                    String summary,
                                    String text,
                                    List<ImageAsset> images,
                                    AudioAsset audio,
                                    List<FileInsight> files,
                                    List<String> notices) {
    }

    public record ImageAsset(String fileName,
                             String mediaType,
                             String dataUrl,
                             String source) {
    }

    public record AudioAsset(String fileName,
                             String mediaType,
                             String dataUrl) {
    }

    public record FileInsight(String fileName,
                              String category,
                              String extractedText,
                              int extractedImageCount) {
    }

    public record TaxiLinkRequest(Double originLongitude,
                                  Double originLatitude,
                                  String originName,
                                  String destination) {
    }

    public record TaxiLinkResponse(boolean success,
                                   String message,
                                   String originName,
                                   String destinationName,
                                   String rawText,
                                   String appLink,
                                   double originLongitude,
                                   double originLatitude,
                                   double destinationLongitude,
                                   double destinationLatitude) {
    }

    public record AroundSearchRequest(Double longitude,
                                      Double latitude,
                                      String keywords,
                                      String types,
                                      Integer radius,
                                      Integer limit) {
        public Integer radius() {
            return radius == null || radius < 1 ? 1000 : radius;
        }

        public Integer limit() {
            return limit == null || limit < 1 ? 10 : limit;
        }
    }

    public record RouteSearchRequest(Double originLongitude,
                                     Double originLatitude,
                                     String destination,
                                     String mode) {
    }

    public record ReminderRequest(String title,
                                  String remindAt,
                                  String message) {
    }

    public record ScheduleRequest(String title,
                                  String startAt,
                                  String endAt,
                                  String notes) {
    }

    public record AutomationResponse(boolean success,
                                     String type,
                                     String title,
                                     String summary,
                                     String content,
                                     List<String> ids) {
    }

    private record PreparedUpload(List<String> imageDataUrls,
                                  List<FileInsight> fileInfos,
                                  List<String> notices) {
        boolean hasVisualInputs() {
            return imageDataUrls != null && !imageDataUrls.isEmpty();
        }
    }

    private record PlaceInfo(double longitude,
                             double latitude,
                             String displayName) {
    }
}
