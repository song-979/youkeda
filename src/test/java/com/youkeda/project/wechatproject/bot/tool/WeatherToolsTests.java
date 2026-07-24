package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherToolsTests {

    @Test
    void currentWeatherRequestIncludesSignatureWhenPrivateKeyIsConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(matchesPattern("https://restapi\\.amap\\.com/v3/weather/weatherInfo\\?.*")))
                .andExpect(queryParam("key", "test-amap-key"))
                .andExpect(queryParam("city", "330106"))
                .andExpect(queryParam("extensions", "base"))
                .andExpect(queryParam("output", "JSON"))
                .andExpect(queryParam("sig", matchesPattern("[0-9a-f]{32}")))
                .andRespond(withSuccess("""
                        {
                          "status":"1",
                          "info":"OK",
                          "infocode":"10000",
                          "lives":[{
                            "province":"浙江",
                            "city":"杭州市",
                            "adcode":"330106",
                            "weather":"晴",
                            "temperature":"31",
                            "winddirection":"东",
                            "windpower":"≤3",
                            "humidity":"60",
                            "reporttime":"2026-07-23 20:20:00"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        WeatherTools.WeatherProperties properties = new WeatherTools.WeatherProperties();
        properties.setAmapKey("test-amap-key");
        properties.setAmapPrivateKey("test-private-key");

        String result = new WeatherTools(properties, restTemplate).getCurrentWeather("330106");

        assertThat(result)
                .contains("地点：浙江，杭州市")
                .contains("天气：晴")
                .contains("实时气温：31°C");
        server.verify();
    }
}
