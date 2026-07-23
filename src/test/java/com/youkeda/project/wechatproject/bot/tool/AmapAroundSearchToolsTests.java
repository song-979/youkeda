package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapAroundSearchToolsTests {

    @Test
    void searchesAroundWithFixedAmapKey() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://restapi.amap.com/v3/place/around?key=d7db5a1d05aed595cac96d966a7a3471&location=120.143222,30.236064&radius=1500&offset=5&page=1&sortrule=distance&extensions=base&output=JSON&keywords=%E5%92%96%E5%95%A1&types=050000"))
                .andExpect(queryParam("key", "d7db5a1d05aed595cac96d966a7a3471"))
                .andExpect(queryParam("location", "120.143222,30.236064"))
                .andExpect(queryParam("radius", "1500"))
                .andExpect(queryParam("offset", "5"))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParam("sortrule", "distance"))
                .andExpect(queryParam("extensions", "base"))
                .andExpect(queryParam("output", "JSON"))
                .andExpect(queryParam("keywords", "\u5496\u5561"))
                .andExpect(queryParam("types", "050000"))
                .andRespond(withSuccess("""
                        {
                          "status":"1",
                          "info":"OK",
                          "infocode":"10000",
                          "count":"1",
                          "pois":[{
                            "id":"B0FFH4KZ8B",
                            "name":"\u661f\u5df4\u514b",
                            "address":"\u9f99\u4e95\u8def1\u53f7",
                            "pname":"\u6d59\u6c5f\u7701",
                            "cityname":"\u676d\u5dde\u5e02",
                            "adname":"\u897f\u6e56\u533a",
                            "adcode":"330106",
                            "location":"120.140000,30.240000",
                            "distance":"526"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        String result = new AmapAroundSearchTools(restTemplate)
                .searchPlacesAround("120.143222,30.236064", "\u5496\u5561", "050000", 1500, 5);

        assertThat(result)
                .contains("\u9ad8\u5fb7\u5468\u8fb9\u641c\u7d22\u7ed3\u679c")
                .contains("\u661f\u5df4\u514b")
                .contains("id\uff1aB0FFH4KZ8B")
                .contains("\u8ddd\u79bb\uff1a526\u7c73")
                .contains("\u5730\u5740\uff1a\u9f99\u4e95\u8def1\u53f7")
                .contains("\u533a\u57df\uff1a\u6d59\u6c5f\u7701\uff0c\u676d\u5dde\u5e02\uff0c\u897f\u6e56\u533a")
                .contains("adcode\uff1a330106")
                .contains("\u5750\u6807\uff1a120.140000,30.240000")
                .contains("\u5df2\u6839\u636e\u641c\u7d22\u534a\u5f84\u81ea\u52a8\u8c03\u6574\u5730\u56fe\u7ea7\u522b\uff1azoom=14")
                .contains("\u9ad8\u5fb7\u9759\u6001\u5730\u56fe")
                .contains("https://restapi.amap.com/v3/staticmap")
                .contains("location=120.143222,30.236064")
                .contains("zoom=14")
                .contains("markers=large,0xFF0000,A:120.143222,30.236064");
        server.verify();
    }
}
