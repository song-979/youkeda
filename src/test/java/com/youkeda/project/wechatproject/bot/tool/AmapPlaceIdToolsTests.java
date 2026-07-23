package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapPlaceIdToolsTests {

    @Test
    void queriesPlaceIdsWithFixedAmapKey() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://restapi.amap.com/v3/place/text?key=d7db5a1d05aed595cac96d966a7a3471&keywords=西湖&offset=3&page=1&extensions=base&output=JSON&city=杭州&citylimit=true&types=110000"))
                .andExpect(queryParam("key", "d7db5a1d05aed595cac96d966a7a3471"))
                .andExpect(queryParam("keywords", "\u897f\u6e56"))
                .andExpect(queryParam("city", "\u676d\u5dde"))
                .andExpect(queryParam("citylimit", "true"))
                .andExpect(queryParam("types", "110000"))
                .andExpect(queryParam("offset", "3"))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParam("extensions", "base"))
                .andExpect(queryParam("output", "JSON"))
                .andRespond(withSuccess("""
                        {
                          "status":"1",
                          "info":"OK",
                          "infocode":"10000",
                          "count":"1",
                          "pois":[{
                            "id":"B023B0UB9P",
                            "name":"\u897f\u6e56\u98ce\u666f\u540d\u80dc\u533a",
                            "address":"\u9f99\u4e95\u8def1\u53f7",
                            "pname":"\u6d59\u6c5f\u7701",
                            "cityname":"\u676d\u5dde\u5e02",
                            "adname":"\u897f\u6e56\u533a",
                            "adcode":"330106",
                            "location":"120.143222,30.236064"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        String result = new AmapPlaceIdTools(restTemplate, "").queryPlaceIds("\u897f\u6e56", "\u676d\u5dde", "110000", 3);

        assertThat(result)
                .contains("\u9ad8\u5fb7\u5730\u70b9\u0049\u0044\u67e5\u8be2\u7ed3\u679c")
                .contains("\u897f\u6e56\u98ce\u666f\u540d\u80dc\u533a")
                .contains("id\uff1aB023B0UB9P")
                .contains("\u5730\u5740\uff1a\u9f99\u4e95\u8def1\u53f7")
                .contains("\u533a\u57df\uff1a\u6d59\u6c5f\u7701\uff0c\u676d\u5dde\u5e02\uff0c\u897f\u6e56\u533a")
                .contains("adcode\uff1a330106")
                .contains("\u5750\u6807\uff1a120.143222,30.236064");
        server.verify();
    }
}
