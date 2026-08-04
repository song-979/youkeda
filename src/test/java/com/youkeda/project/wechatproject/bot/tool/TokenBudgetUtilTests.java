package com.youkeda.project.wechatproject.bot.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetUtilTests {

    @Test
    void slicesLongArchivedContentByTokenOffset() {
        String content = "first marker " + "detail item ".repeat(2_000);

        TokenBudgetUtil.TokenSlice first = TokenBudgetUtil.sliceByTokens(content, 0, 100);
        TokenBudgetUtil.TokenSlice second = TokenBudgetUtil.sliceByTokens(content, first.nextOffsetToken(), 100);

        assertThat(first.content()).contains("first marker");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextOffsetToken()).isPositive();
        assertThat(second.content()).doesNotContain("first marker");
        assertThat(second.nextOffsetToken()).isGreaterThan(first.nextOffsetToken());
    }

    @Test
    void countsUnicodeAndTruncatesAtReadableBoundary() {
        String text = "first paragraph with several words.\n\nsecond paragraph with more words.\n\nthird paragraph";
        String truncated = TokenBudgetUtil.truncateAtBoundary(text, 10);

        assertThat(TokenBudgetUtil.countTokens(text)).isGreaterThan(10);
        assertThat(truncated).endsWith("...[context truncated]");
        assertThat(truncated).doesNotContain("third paragraph");
    }

    @Test
    void leavesTextWithinBudgetUntouched() {
        assertThat(TokenBudgetUtil.truncateAtBoundary("short text", 20)).isEqualTo("short text");
        assertThat(TokenBudgetUtil.countTokens(null)).isZero();
    }
}
