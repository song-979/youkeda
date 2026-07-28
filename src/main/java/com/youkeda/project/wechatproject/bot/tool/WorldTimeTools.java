package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

public class WorldTimeTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(WorldTimeTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WORLDTIME_URL = "https://uapis.cn/api/v1/misc/worldtime";

    private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
            Map.entry("纽约", "NewYork"),
            Map.entry("洛杉矶", "LosAngeles"),
            Map.entry("旧金山", "SanFrancisco"),
            Map.entry("芝加哥", "Chicago"),
            Map.entry("华盛顿", "WashingtonDC"),
            Map.entry("伦敦", "London"),
            Map.entry("巴黎", "Paris"),
            Map.entry("柏林", "Berlin"),
            Map.entry("莫斯科", "Moscow"),
            Map.entry("悉尼", "Sydney"),
            Map.entry("北京", "Beijing"),
            Map.entry("上海", "Shanghai"),
            Map.entry("广州", "Guangzhou"),
            Map.entry("深圳", "Shenzhen"),
            Map.entry("杭州", "Hangzhou"),
            Map.entry("成都", "Chengdu"),
            Map.entry("武汉", "Wuhan"),
            Map.entry("南京", "Nanjing"),
            Map.entry("重庆", "Chongqing"),
            Map.entry("西安", "Xian"),
            Map.entry("香港", "HongKong"),
            Map.entry("新加坡", "Singapore"),
            Map.entry("曼谷", "Bangkok"),
            Map.entry("首尔", "Seoul"),
            Map.entry("釜山", "Busan"),
            Map.entry("马尼拉", "Manila"),
            Map.entry("雅加达", "Jakarta"),
            Map.entry("吉隆坡", "KualaLumpur"),
            Map.entry("河内", "Hanoi"),
            Map.entry("胡志明市", "HoChiMinh"),
            Map.entry("仰光", "Yangon"),
            Map.entry("达卡", "Dhaka"),
            Map.entry("新德里", "NewDelhi"),
            Map.entry("孟买", "Mumbai"),
            Map.entry("加尔各答", "Kolkata"),
            Map.entry("班加罗尔", "Bangalore"),
            Map.entry("伊斯兰堡", "Islamabad"),
            Map.entry("卡拉奇", "Karachi"),
            Map.entry("拉合尔", "Lahore"),
            Map.entry("加德满都", "Kathmandu"),
            Map.entry("科伦坡", "Colombo"),
            Map.entry("马累", "Male"),
            Map.entry("伊斯坦布尔", "Istanbul"),
            Map.entry("安卡拉", "Ankara"),
            Map.entry("迪拜", "Dubai"),
            Map.entry("阿布扎比", "AbuDhabi"),
            Map.entry("多哈", "Doha"),
            Map.entry("利雅得", "Riyadh"),
            Map.entry("吉达", "Jeddah"),
            Map.entry("德黑兰", "Tehran"),
            Map.entry("巴格达", "Baghdad"),
            Map.entry("耶路撒冷", "Jerusalem"),
            Map.entry("特拉维夫", "TelAviv"),
            Map.entry("开罗", "Cairo"),
            Map.entry("亚历山大", "Alexandria"),
            Map.entry("拉各斯", "Lagos"),
            Map.entry("阿布贾", "Abuja"),
            Map.entry("内罗毕", "Nairobi"),
            Map.entry("亚的斯亚贝巴", "AddisAbaba"),
            Map.entry("约翰内斯堡", "Johannesburg"),
            Map.entry("开普敦", "CapeTown"),
            Map.entry("达累斯萨拉姆", "DarEsSalaam"),
            Map.entry("坎帕拉", "Kampala"),
            Map.entry("拉巴特", "Rabat"),
            Map.entry("卡萨布兰卡", "Casablanca"),
            Map.entry("阿尔及尔", "Algiers"),
            Map.entry("突尼斯", "Tunis"),
            Map.entry("的黎波里", "Tripoli"),
            Map.entry("喀土穆", "Khartoum"),
            Map.entry("罗马", "Rome"),
            Map.entry("米兰", "Milan"),
            Map.entry("马德里", "Madrid"),
            Map.entry("巴塞罗那", "Barcelona"),
            Map.entry("里斯本", "Lisbon"),
            Map.entry("雅典", "Athens"),
            Map.entry("维也纳", "Vienna"),
            Map.entry("华沙", "Warsaw"),
            Map.entry("布拉格", "Prague"),
            Map.entry("布达佩斯", "Budapest"),
            Map.entry("布加勒斯特", "Bucharest"),
            Map.entry("索菲亚", "Sofia"),
            Map.entry("贝尔格莱德", "Belgrade"),
            Map.entry("萨格勒布", "Zagreb"),
            Map.entry("卢布尔雅那", "Ljubljana"),
            Map.entry("布鲁塞尔", "Brussels"),
            Map.entry("阿姆斯特丹", "Amsterdam"),
            Map.entry("哥本哈根", "Copenhagen"),
            Map.entry("斯德哥尔摩", "Stockholm"),
            Map.entry("奥斯陆", "Oslo"),
            Map.entry("赫尔辛基", "Helsinki"),
            Map.entry("雷克雅未克", "Reykjavik"),
            Map.entry("都柏林", "Dublin"),
            Map.entry("苏黎世", "Zurich"),
            Map.entry("日内瓦", "Geneva"),
            Map.entry("卢森堡", "Luxembourg"),
            Map.entry("摩纳哥", "Monaco"),
            Map.entry("圣彼得堡", "SaintPetersburg"),
            Map.entry("基辅", "Kyiv"),
            Map.entry("明斯克", "Minsk"),
            Map.entry("基希讷乌", "Chisinau"),
            Map.entry("第比利斯", "Tbilisi"),
            Map.entry("巴库", "Baku"),
            Map.entry("埃里温", "Yerevan"),
            Map.entry("东京", "Tokyo"),
            Map.entry("大阪", "Osaka"),
            Map.entry("名古屋", "Nagoya"),
            Map.entry("福冈", "Fukuoka"),
            Map.entry("札幌", "Sapporo"),
            Map.entry("京都", "Kyoto"),
            Map.entry("横滨", "Yokohama"),
            Map.entry("神户", "Kobe"),
            Map.entry("台北", "Taipei"),
            Map.entry("高雄", "Kaohsiung"),
            Map.entry("台中", "Taichung"),
            Map.entry("乌兰巴托", "Ulaanbaatar"),
            Map.entry("平壤", "Pyongyang"),
            Map.entry("多伦多", "Toronto"),
            Map.entry("温哥华", "Vancouver"),
            Map.entry("蒙特利尔", "Montreal"),
            Map.entry("卡尔加里", "Calgary"),
            Map.entry("渥太华", "Ottawa"),
            Map.entry("墨西哥城", "MexicoCity"),
            Map.entry("坎昆", "Cancun"),
            Map.entry("瓜达拉哈拉", "Guadalajara"),
            Map.entry("圣保罗", "SaoPaulo"),
            Map.entry("里约热内卢", "RioDeJaneiro"),
            Map.entry("巴西利亚", "Brasilia"),
            Map.entry("布宜诺斯艾利斯", "BuenosAires"),
            Map.entry("科尔多瓦", "Cordoba"),
            Map.entry("圣地亚哥", "Santiago"),
            Map.entry("利马", "Lima"),
            Map.entry("波哥大", "Bogota"),
            Map.entry("麦德林", "Medellin"),
            Map.entry("加拉加斯", "Caracas"),
            Map.entry("基多", "Quito"),
            Map.entry("拉巴斯", "LaPaz"),
            Map.entry("蒙得维的亚", "Montevideo"),
            Map.entry("亚松森", "Asuncion"),
            Map.entry("巴拿马城", "PanamaCity"),
            Map.entry("圣何塞", "SanJose"),
            Map.entry("哈瓦那", "Havana"),
            Map.entry("金斯敦", "Kingston"),
            Map.entry("拿骚", "Nassau"),
            Map.entry("墨尔本", "Melbourne"),
            Map.entry("布里斯班", "Brisbane"),
            Map.entry("珀斯", "Perth"),
            Map.entry("阿德莱德", "Adelaide"),
            Map.entry("达尔文", "Darwin"),
            Map.entry("霍巴特", "Hobart"),
            Map.entry("奥克兰", "Auckland"),
            Map.entry("惠灵顿", "Wellington"),
            Map.entry("基督城", "Christchurch"),
            Map.entry("苏瓦", "Suva"),
            Map.entry("努库阿洛法", "Nukualofa"),
            Map.entry("阿皮亚", "Apia"),
            Map.entry("帕皮提", "Papeete")
    );

    private final RestTemplate restTemplate;

    public WorldTimeTools() {
        this(createRestTemplate());
    }

    WorldTimeTools(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Tool(name = "get_world_time",
          description = "Get the current date, time and timezone for any city or region worldwide. Use this when users ask about the current time in a specific city or country.")
    public String getWorldTime(
            @ToolParam(description = "City or region name. Supports English city names (e.g. 'Tokyo', 'NewYork', 'London') and Chinese city names (e.g. '东京', '纽约', '伦敦').")
            String city) {
        if (city == null || city.isBlank()) {
            return "世界时间查询失败：请提供城市名称。";
        }

        try {
            String queryCity = normalizeCity(city);
            JsonNode root = requestWorldTime(queryCity);
            if (root.has("error")) {
                String error = root.path("error").asText();
                return "世界时间查询失败：未找到城市\"" + city + "\"的时间信息（" + error + "）。请尝试使用英文城市名称，例如 Tokyo、London、NewYork。";
            }
            return formatWorldTime(root);
        } catch (Exception e) {
            log.warn("world time tool failed: city={}, error={}", city, e.getMessage());
            return "世界时间查询失败：" + e.getMessage();
        }
    }

    private static String normalizeCity(String city) {
        String trimmed = city.trim();
        String alias = CITY_ALIASES.get(trimmed);
        if (alias != null) {
            return alias;
        }
        // Remove spaces for multi-word English city names
        return trimmed.replace(" ", "");
    }

    private JsonNode requestWorldTime(String city) {
        var url = UriComponentsBuilder.fromUriString(WORLDTIME_URL)
                .queryParam("city", city)
                .build().encode().toUri();

        return parseJson(restTemplate.getForObject(url, String.class));
    }

    private static String formatWorldTime(JsonNode root) {
        String query = text(root.path("query"));
        String timezone = text(root.path("timezone"));
        String datetime = text(root.path("datetime"));
        String weekday = text(root.path("weekday"));
        String offsetString = text(root.path("offset_string"));

        return """
                城市：%s
                时区：%s
                日期时间：%s
                星期：%s
                时区偏移：%s
                """.formatted(
                valueOrUnknown(query),
                valueOrUnknown(timezone),
                valueOrUnknown(datetime),
                valueOrUnknown(weekday),
                valueOrUnknown(offsetString)
        ).trim();
    }

    private static JsonNode parseJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("世界时间接口返回数据解析失败", e);
        }
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    private static String valueOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : "未知";
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    @ConfigurationProperties(prefix = "agent.tools.worldtime")
    public static class WorldTimeProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
