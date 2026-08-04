package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil;

public class CharacterContextTokenEstimator implements ContextTokenEstimator {

    @Override
    public int estimate(String text) {
        return TokenBudgetUtil.countTokens(text);
    }
}
