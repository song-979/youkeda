package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmapStaticMapToolsTests {

    @Test
    void generatesStaticMapUrlWithFixedAmapKeyAndDefaults() {
        String result = new AmapStaticMapTools()
                .generateStaticMap("120.143222,30.236064", null, null, null, null, null, null, false);

        assertThat(result)
                .contains("\u9ad8\u5fb7\u9759\u6001\u5730\u56fe")
                .contains("https://restapi.amap.com/v3/staticmap")
                .contains("key=d7db5a1d05aed595cac96d966a7a3471")
                .contains("location=120.143222,30.236064")
                .contains("zoom=14")
                .contains("size=600*400")
                .contains("scale=1")
                .contains("markers=mid,,A:120.143222,30.236064")
                .contains("traffic=0");
    }

    @Test
    void generatesStaticMapUrlWithOptionalOverlays() {
        String result = new AmapStaticMapTools().generateStaticMap(
                "120.143222,30.236064",
                16,
                "800*600",
                2,
                "large,0x008000,A:120.143222,30.236064",
                "\u897f\u6e56,2,0,16,0xFFFFFF,0x008000:120.143222,30.236064",
                "10,0x0000ff,1,,120.143222,30.236064;120.150000,30.240000",
                true);

        assertThat(result)
                .contains("zoom=16")
                .contains("size=800*600")
                .contains("scale=2")
                .contains("markers=large,0x008000,A:120.143222,30.236064")
                .contains("labels=")
                .contains("paths=")
                .contains("traffic=1");
    }
}
