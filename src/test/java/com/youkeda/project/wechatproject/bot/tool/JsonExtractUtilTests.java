package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExtractUtilTests {

    @Test
    void extractsFirstCompleteObjectWithoutCountingBracesInsideStrings() {
        String raw = "reasoning {not json}\n```json\n{\"text\":\"a } and \\\"{\\\"\",\"ok\":true}\n``` trailing";

        assertThat(JsonExtractUtil.extractJsonObject(raw))
                .isEqualTo("{\"text\":\"a } and \\\"{\\\"\",\"ok\":true}");
    }

    @Test
    void skipsIncompletePrefixAndReturnsNextObject() {
        assertThat(JsonExtractUtil.extractJsonObject("prefix { broken then {\"valid\":1}"))
                .isEqualTo("{\"valid\":1}");
        assertThat(JsonExtractUtil.extractJsonObject(null)).isNull();
    }
}
