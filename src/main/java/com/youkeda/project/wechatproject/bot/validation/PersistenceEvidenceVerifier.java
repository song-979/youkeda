package com.youkeda.project.wechatproject.bot.validation;

import com.youkeda.project.wechatproject.bot.tool.chat.AutomationEvidenceContext;

import java.util.List;

public interface PersistenceEvidenceVerifier {

    Verification verify(List<AutomationEvidenceContext.Evidence> evidence, String recipientId);

    record Verification(boolean valid, int verifiedCount, String message) {
        public static Verification accepted(int count) { return new Verification(true, count, null); }
        public static Verification rejected(String message) { return new Verification(false, 0, message); }
    }
}
