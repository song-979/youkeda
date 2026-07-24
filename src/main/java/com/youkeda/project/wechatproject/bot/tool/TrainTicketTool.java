package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TrainTicketTool implements ToolService.ProjectTool {

    @Override
    public String category() { return "information"; }

    private static final Logger log = LoggerFactory.getLogger(TrainTicketTool.class);
    private static final String API_BASE = "https://kyfw.12306.cn";
    private static final String INIT_URL = API_BASE + "/otn/leftTicket/init";
    private static final String QUERY_URL = API_BASE + "/otn/leftTicket/queryG";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final Map<String, String> STATION_NAME_TO_CODE = new LinkedHashMap<>();
    private static final Map<String, String> STATION_CODE_TO_NAME = new LinkedHashMap<>();
    private static final Map<String, String> CITY_TO_CODE = new LinkedHashMap<>();

    static {
        // 直辖市/省会/热门城市 → 默认车站 telecode
        String[][] stations = {
            {"北京", "BJP", "北京"}, {"北京南", "VNP", "北京南"}, {"北京西", "BXP", "北京西"},
            {"上海", "SHH", "上海"}, {"上海虹桥", "AOH", "上海虹桥"}, {"上海南", "SNH", "上海南"},
            {"广州", "GZQ", "广州"}, {"广州南", "IZQ", "广州南"}, {"广州东", "GGQ", "广州东"},
            {"深圳", "SZQ", "深圳"}, {"深圳北", "IOQ", "深圳北"},
            {"杭州", "HZH", "杭州"}, {"杭州东", "HGH", "杭州东"}, {"杭州南", "XHH", "杭州南"},
            {"南京", "NJH", "南京"}, {"南京南", "NKH", "南京南"},
            {"武汉", "WHN", "武汉"}, {"汉口", "HKN", "汉口"}, {"武昌", "WCN", "武昌"},
            {"成都", "CDW", "成都"}, {"成都东", "ICW", "成都东"},
            {"重庆", "CQW", "重庆"}, {"重庆北", "CUW", "重庆北"}, {"重庆西", "CXW", "重庆西"},
            {"西安", "XAY", "西安"}, {"西安北", "EAY", "西安北"},
            {"郑州", "ZZF", "郑州"}, {"郑州东", "ZAF", "郑州东"},
            {"长沙", "CSQ", "长沙"}, {"长沙南", "CWQ", "长沙南"},
            {"天津", "TJP", "天津"}, {"天津西", "TXP", "天津西"},
            {"苏州", "SZH", "苏州"}, {"苏州北", "OHH", "苏州北"},
            {"合肥", "HFH", "合肥"}, {"合肥南", "ENH", "合肥南"},
            {"济南", "JNK", "济南"}, {"济南西", "JGK", "济南西"},
            {"青岛", "QDK", "青岛"}, {"青岛北", "QHK", "青岛北"},
            {"福州", "FZS", "福州"}, {"福州南", "FYS", "福州南"},
            {"厦门", "XMS", "厦门"}, {"厦门北", "XKS", "厦门北"},
            {"南昌", "NCG", "南昌"}, {"南昌西", "NXG", "南昌西"},
            {"昆明", "KMM", "昆明"}, {"昆明南", "KOM", "昆明南"},
            {"贵阳", "GIW", "贵阳"}, {"贵阳北", "KQW", "贵阳北"},
            {"南宁", "NNZ", "南宁"}, {"南宁东", "NFZ", "南宁东"},
            {"哈尔滨", "HBB", "哈尔滨"}, {"哈尔滨西", "VBB", "哈尔滨西"},
            {"长春", "CCT", "长春"}, {"长春西", "CRT", "长春西"},
            {"沈阳", "SYT", "沈阳"}, {"沈阳北", "SBT", "沈阳北"},
            {"大连", "DLT", "大连"}, {"大连北", "DFT", "大连北"},
            {"石家庄", "SJP", "石家庄"},
            {"太原", "TYV", "太原"}, {"太原南", "TNV", "太原南"},
            {"兰州", "LZJ", "兰州"}, {"兰州西", "LAJ", "兰州西"},
            {"乌鲁木齐", "WAR", "乌鲁木齐"},
            {"呼和浩特", "HHC", "呼和浩特"}, {"呼和浩特东", "NDC", "呼和浩特东"},
            {"西宁", "XNO", "西宁"},
            {"银川", "YIJ", "银川"},
            {"拉萨", "LSO", "拉萨"},
            {"海口", "VUQ", "海口"},
            {"三亚", "SEQ", "三亚"},
            {"桂林", "GLZ", "桂林"}, {"桂林北", "GBZ", "桂林北"},
            {"洛阳", "LYF", "洛阳"}, {"洛阳龙门", "LLF", "洛阳龙门"},
            {"徐州", "XCH", "徐州"}, {"徐州东", "UUH", "徐州东"},
            {"温州", "RZH", "温州"}, {"温州南", "VRH", "温州南"},
            {"宁波", "NGH", "宁波"},
            {"无锡", "WXH", "无锡"}, {"无锡东", "WGH", "无锡东"},
            {"黄山", "HKH", "黄山"}, {"黄山北", "NYH", "黄山北"},
            {"珠海", "ZHQ", "珠海"},
            {"香港西九龙", "XJA", "香港西九龙"},
        };
        for (String[] s : stations) {
            STATION_NAME_TO_CODE.put(s[0], s[1]);
            STATION_CODE_TO_NAME.put(s[1], s[2]);
        }
        // 城市名映射到主要车站
        String[][] cities = {
            {"北京", "BJP"}, {"上海", "SHH"}, {"广州", "GZQ"}, {"深圳", "SZQ"},
            {"杭州", "HZH"}, {"南京", "NJH"}, {"武汉", "WHN"}, {"成都", "CDW"},
            {"重庆", "CQW"}, {"西安", "XAY"}, {"郑州", "ZZF"}, {"长沙", "CSQ"},
            {"天津", "TJP"}, {"苏州", "SZH"}, {"合肥", "HFH"}, {"济南", "JNK"},
            {"青岛", "QDK"}, {"福州", "FZS"}, {"厦门", "XMS"}, {"南昌", "NCG"},
            {"昆明", "KMM"}, {"贵阳", "GIW"}, {"南宁", "NNZ"}, {"哈尔滨", "HBB"},
            {"长春", "CCT"}, {"沈阳", "SYT"}, {"大连", "DLT"}, {"石家庄", "SJP"},
            {"太原", "TYV"}, {"兰州", "LZJ"}, {"乌鲁木齐", "WAR"}, {"呼和浩特", "HHC"},
            {"西宁", "XNO"}, {"银川", "YIJ"}, {"拉萨", "LSO"}, {"海口", "VUQ"},
            {"桂林", "GLZ"}, {"洛阳", "LYF"}, {"徐州", "XCH"}, {"温州", "RZH"},
            {"宁波", "NGH"}, {"无锡", "WXH"}, {"黄山", "HKH"},
        };
        for (String[] c : cities) {
            CITY_TO_CODE.put(c[0], c[1]);
        }
    }

    private static final Map<String, String> SEAT_NAMES = Map.ofEntries(
        Map.entry("swz", "商务座"), Map.entry("tz", "特等座"),
        Map.entry("zy", "一等座"), Map.entry("ze", "二等座"),
        Map.entry("gr", "高软卧"), Map.entry("rw", "软卧"),
        Map.entry("yw", "硬卧"), Map.entry("rz", "软座"),
        Map.entry("yz", "硬座"), Map.entry("wz", "无座"),
        Map.entry("srrb", "动卧"), Map.entry("qt", "其他")
    );

    private final HttpClient httpClient;

    public TrainTicketTool() {
        this.httpClient = newHttpClient();
    }

    private static HttpClient newHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("failed to create SSL context for 12306", e);
        }
    }

    @Tool(name = "search_train_tickets",
          description = "查询12306火车票余票信息。输入出发地、目的地和日期，返回所有车次、座位类型、票价和余票情况。支持按高铁/动车/直达等类型筛选。")
    public String searchTrainTickets(
            @ToolParam(description = "出发城市或车站名，如 杭州、杭州东、上海虹桥。也可直接输入车站telecode如 HZH。") String fromStation,
            @ToolParam(description = "到达城市或车站名，如 上海、北京南。也可直接输入车站telecode如 SHH。") String toStation,
            @ToolParam(description = "出发日期，格式 yyyy-MM-dd，如 2026-07-25") String date,
            @ToolParam(description = "车次筛选，可选 G(高铁/城际) D(动车) Z(直达) T(特快) K(快速)，不填则全部显示。如 'GD' 表示只看高铁和动车。") String trainFilter,
            @ToolParam(description = "最早出发时间(0-23)，默认0。如 8 表示只看8点之后出发的车。") Integer earliestHour,
            @ToolParam(description = "最晚出发时间(0-24)，默认24。如 20 表示只看20点之前出发的车。") Integer latestHour,
            @ToolParam(description = "排序方式：startTime(按出发时间)、duration(按历时)，不填则不排序。") String sortBy,
            @ToolParam(description = "最大返回条数，默认10。") Integer limit) {
        try {
            String fromCode = resolveStationCode(fromStation);
            String toCode = resolveStationCode(toStation);
            if (fromCode == null) {
                return "未找到出发站「" + fromStation + "」，请使用城市名（如 杭州）或具体站名（如 杭州东）。";
            }
            if (toCode == null) {
                return "未找到到达站「" + toStation + "」，请使用城市名（如 上海）或具体站名（如 上海虹桥）。";
            }
            String dateError = validateTravelDate(date);
            if (dateError != null) {
                return dateError;
            }

            String cookie = fetchCookie();
            if (cookie == null || cookie.isBlank()) {
                return "车票查询失败：无法连接12306服务器获取会话。";
            }

            String queryUrl = QUERY_URL + "?leftTicketDTO.train_date=" + urlEncode(date)
                    + "&leftTicketDTO.from_station=" + fromCode
                    + "&leftTicketDTO.to_station=" + toCode
                    + "&purpose_codes=ADULT";

            HttpRequest request = HttpRequest.newBuilder(URI.create(queryUrl))
                    .header("User-Agent", UA)
                    .header("Referer", INIT_URL)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", cookie)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                return "车票查询失败：12306返回空数据。";
            }

            log.info("12306 query status={}, cookie_len={}, body_len={}", response.statusCode(), cookie.length(), body.length);
            String textBody = stripBomPrefix(new String(body, StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !looksLikeJson(textBody)) {
                String preview = textBody.substring(0, Math.min(textBody.length(), 500));
                log.warn("12306 returned non-JSON response (status={}): {}", response.statusCode(), preview);
                return "车票查询失败：12306返回了非JSON响应(status=" + response.statusCode()
                        + ")，可能是查询日期无效、会话失效或接口被拦截。请改查当前可售日期，或通过12306官方渠道确认。";
            }

            JsonNode root = parseJson(textBody);
            int httpStatus = root.path("httpstatus").asInt(0);
            if (httpStatus != 200) {
                String msg = root.path("messages").asText("未知错误");
                return "车票查询失败：" + msg;
            }

            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.size() == 0) {
                return "未查询到 " + date + " 从 " + fromStation + " 到 " + toStation + " 的车次。";
            }

            JsonNode map = root.path("data").path("map");
            List<TicketInfo> tickets = parseTickets(result, map);

            // 筛选
            String filter = trainFilter != null ? trainFilter.trim().toUpperCase() : "";
            int early = earliestHour != null ? earliestHour : 0;
            int late = latestHour != null ? latestHour : 24;
            tickets = filterTickets(tickets, filter, early, late);

            // 排序
            if ("startTime".equals(sortBy)) {
                tickets.sort((a, b) -> a.startTime.compareTo(b.startTime));
            } else if ("duration".equals(sortBy)) {
                tickets.sort((a, b) -> Integer.compare(a.durationMin, b.durationMin));
            }

            // 限制条数
            int max = limit != null && limit > 0 ? Math.min(limit, 20) : 10;
            if (tickets.size() > max) {
                tickets = tickets.subList(0, max);
            }

            return formatTickets(tickets, date, fromStation, toStation);
        } catch (Exception e) {
            log.error("train ticket search failed: from={}, to={}, date={}, error={}",
                    fromStation, toStation, date, e.getMessage());
            return "车票查询失败：" + e.getMessage();
        }
    }

    private static String validateTravelDate(String date) {
        LocalDate travelDate;
        try {
            travelDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return "车票查询失败：日期格式不正确，请使用 yyyy-MM-dd，例如 2026-07-25。";
        }

        LocalDate today = LocalDate.now();
        if (travelDate.isBefore(today)) {
            return "车票查询失败：不能查询过去日期的车票。今天是 " + today + "，你查询的是 " + travelDate + "。";
        }
        return null;
    }

    private static boolean looksLikeJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '{' || first == '[';
    }

    private static JsonNode parseJson(String text) throws java.io.IOException {
        return JSON_MAPPER.readTree(stripBomPrefix(text));
    }

    private static JsonNode parseJson(byte[] body) throws java.io.IOException {
        try {
            return JSON_MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            String text = new String(body, StandardCharsets.UTF_8);
            return JSON_MAPPER.readTree(stripBomPrefix(text));
        }
    }

    private static String stripBomPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch != '\uFEFF' && !Character.isWhitespace(ch)) {
                break;
            }
            index++;
        }
        return index == 0 ? text : text.substring(index);
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private String fetchCookie() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(INIT_URL))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            java.util.List<String> allCookies = response.headers().allValues("Set-Cookie");
            StringBuilder sb = new StringBuilder();
            for (String cookie : allCookies) {
                String kv = cookie.split(";")[0].trim();
                if (!kv.isEmpty()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(kv);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("failed to get 12306 cookie: {}", e.getMessage());
            return null;
        }
    }

    private String resolveStationCode(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();
        // 去掉"站"后缀
        if (s.endsWith("站")) s = s.substring(0, s.length() - 1);
        // 先查精确站名
        if (STATION_NAME_TO_CODE.containsKey(s)) return STATION_NAME_TO_CODE.get(s);
        // 再查城市名
        if (CITY_TO_CODE.containsKey(s)) return CITY_TO_CODE.get(s);
        // 最后检查是否是 telecode（3位纯大写字母）
        if (s.matches("[A-Z]{3}")) return s;
        return null;
    }

    private List<TicketInfo> parseTickets(JsonNode result, JsonNode map) {
        List<TicketInfo> tickets = new java.util.ArrayList<>();
        for (JsonNode item : result) {
            String[] fields = item.asText().split("\\|");
            if (fields.length < 37) continue;

            String trainCode = fields[3]; // station_train_code
            String startTime = fields[8];
            String arriveTime = fields[9];
            String lishi = fields[10]; // 历时 hh:mm
            String fromTelecode = fields[6];
            String toTelecode = fields[7];
            String trainDate = fields[13];
            String ypInfoNew = fields.length > 42 ? fields[42] : "";
            String seatDiscount = fields.length > 53 ? fields[53] : "";

            String fromName = map.has(fromTelecode) ? map.get(fromTelecode).asText() : fromTelecode;
            String toName = map.has(toTelecode) ? map.get(toTelecode).asText() : toTelecode;

            int durMin = parseDuration(lishi);
            List<SeatInfo> seats = parseSeats(ypInfoNew, seatDiscount, fields);

            tickets.add(new TicketInfo(trainCode, trainDate, startTime, arriveTime, lishi, durMin,
                    fromName, toName, fromTelecode, toTelecode, seats));
        }
        return tickets;
    }

    private List<SeatInfo> parseSeats(String ypInfo, String discountInfo, String[] rawFields) {
        List<SeatInfo> seats = new java.util.ArrayList<>();
        if (ypInfo == null || ypInfo.length() < 10) return seats;

        for (int i = 0; i < ypInfo.length() / 10; i++) {
            int offset = i * 10;
            if (offset + 10 > ypInfo.length()) break;
            String seatTypeCode = ypInfo.substring(offset, offset + 1);
            String priceStr = ypInfo.substring(offset + 1, offset + 6);
            double price = 0;
            try { price = Integer.parseInt(priceStr) / 10.0; } catch (NumberFormatException ignored) {}

            String seatName = SEAT_NAMES.getOrDefault(seatTypeCode.toLowerCase(), "其他");

            // 特等座特殊处理
            if ("P".equals(seatTypeCode)) seatName = "特等座";
            // 从 rawFields 中读取票量
            String num = getSeatNum(seatTypeCode, rawFields);

            if (seatName.equals("其他") && num != null && num.equals("0")) continue;
            seats.add(new SeatInfo(seatName, price, formatTicketNum(num)));
        }
        return seats;
    }

    private static final Map<String, String> CODE_TO_SHORT = Map.ofEntries(
        Map.entry("9", "swz"), Map.entry("P", "tz"), Map.entry("M", "zy"),
        Map.entry("O", "ze"), Map.entry("6", "gr"), Map.entry("4", "rw"),
        Map.entry("3", "yw"), Map.entry("2", "rz"), Map.entry("1", "yz")
    );

    // fields[] 中座位票量的索引位置（从0开始）：[26]swz [27]tz [28]zy [29]ze [30]gr [31]rw [32]yw [33]rz [34]yz [35]wz [36]qt [37]srrb
    private static final Map<String, Integer> SEAT_FIELD_INDEX = Map.ofEntries(
        Map.entry("swz", 26), Map.entry("tz", 27), Map.entry("zy", 28),
        Map.entry("ze", 29), Map.entry("gr", 30), Map.entry("rw", 31),
        Map.entry("yw", 32), Map.entry("rz", 33), Map.entry("yz", 34),
        Map.entry("wz", 35), Map.entry("qt", 36), Map.entry("srrb", 37)
    );

    private String getSeatNum(String seatTypeCode, String[] fields) {
        String shortName = CODE_TO_SHORT.get(seatTypeCode);
        if (shortName != null && SEAT_FIELD_INDEX.containsKey(shortName)) {
            int fi = SEAT_FIELD_INDEX.get(shortName);
            if (fi < fields.length) return fields[fi];
        }
        if ("W".equals(seatTypeCode) && fields.length > 35) return fields[35];
        return "0";
    }

    private static String formatTicketNum(String num) {
        if (num == null || num.isEmpty() || "无".equals(num) || "--".equals(num)) return "无票";
        if ("有".equals(num) || "充足".equals(num)) return "有票";
        try {
            int n = Integer.parseInt(num);
            return n == 0 ? "无票" : "余" + n + "张";
        } catch (NumberFormatException e) {
            return num + "票";
        }
    }

    private List<TicketInfo> filterTickets(List<TicketInfo> tickets, String filter, int early, int late) {
        List<TicketInfo> result = new java.util.ArrayList<>();
        for (TicketInfo t : tickets) {
            // 车次类型筛选
            if (!filter.isEmpty()) {
                boolean match = false;
                for (char c : filter.toCharArray()) {
                    if (matchesFilter(t.trainCode, c)) { match = true; break; }
                }
                if (!match) continue;
            }
            // 时间筛选
            try {
                int hour = Integer.parseInt(t.startTime.split(":")[0]);
                if (hour < early || hour >= late) continue;
            } catch (NumberFormatException ignored) {}
            result.add(t);
        }
        return result;
    }

    private boolean matchesFilter(String trainCode, char filter) {
        return switch (filter) {
            case 'G' -> trainCode.startsWith("G") || trainCode.startsWith("C");
            case 'D' -> trainCode.startsWith("D");
            case 'Z' -> trainCode.startsWith("Z");
            case 'T' -> trainCode.startsWith("T");
            case 'K' -> trainCode.startsWith("K");
            default -> !(trainCode.startsWith("G") || trainCode.startsWith("C") || trainCode.startsWith("D")
                    || trainCode.startsWith("Z") || trainCode.startsWith("T") || trainCode.startsWith("K"));
        };
    }

    private int parseDuration(String lishi) {
        try {
            String[] parts = lishi.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) { return 9999; }
    }

    private String formatTickets(List<TicketInfo> tickets, String date, String from, String to) {
        StringBuilder sb = new StringBuilder();
        sb.append(date).append(" ").append(from).append(" → ").append(to).append(" 车票查询结果：\n");

        for (int i = 0; i < tickets.size(); i++) {
            TicketInfo t = tickets.get(i);
            sb.append("\n─── ").append(i + 1).append(". ").append(t.trainCode)
              .append(" ───\n");
            sb.append("  ").append(t.fromName).append(" → ").append(t.toName)
              .append("  ").append(t.startTime).append(" → ").append(t.arriveTime)
              .append("  历时").append(t.lishi).append("\n");
            for (SeatInfo s : t.seats) {
                sb.append("  ").append(s.name).append("：").append(s.status);
                if (s.price > 0) sb.append(" ¥").append((int) s.price);
                sb.append("\n");
            }
        }
        sb.append("\n共 ").append(tickets.size()).append(" 趟车次。");
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- inner types ----

    private record TicketInfo(
        String trainCode, String trainDate, String startTime, String arriveTime,
        String lishi, int durationMin, String fromName, String toName,
        String fromTelecode, String toTelecode, List<SeatInfo> seats
    ) {}

    private record SeatInfo(String name, double price, String status) {}
}
