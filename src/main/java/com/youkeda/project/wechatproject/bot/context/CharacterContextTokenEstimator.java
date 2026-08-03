package com.youkeda.project.wechatproject.bot.context;

public class CharacterContextTokenEstimator implements ContextTokenEstimator {

    @Override
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk++;
            } else if (!Character.isWhitespace(ch)) {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
