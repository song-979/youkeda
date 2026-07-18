package com.youkeda.project.wechatproject.agent.intent;

import java.util.Collections;
import java.util.Map;

/**
 * 意图识别结果。
 * 包含意图类型和结构化参数（如生图的 prompt）。
 */
public class IntentResult {

    private final IntentType type;
    private final Map<String, Object> params;

    private IntentResult(IntentType type, Map<String, Object> params) {
        this.type = type;
        this.params = params != null ? Collections.unmodifiableMap(params) : Collections.emptyMap();
    }

    public IntentType getType() {
        return type;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    /** 从 params 中提取生图 prompt，不存在则返回 null */
    public String getPrompt() {
        Object p = params.get("prompt");
        return p instanceof String s && !s.isEmpty() ? s : null;
    }

    public boolean isChat() {
        return type == IntentType.CHAT;
    }

    public boolean isImageGen() {
        return type == IntentType.IMAGE_GEN;
    }

    // ---- 工厂方法 ----

    public static IntentResult chat() {
        return new IntentResult(IntentType.CHAT, Collections.emptyMap());
    }

    public static IntentResult imageGen(String prompt) {
        return new IntentResult(IntentType.IMAGE_GEN,
                Collections.singletonMap("prompt", prompt != null ? prompt : ""));
    }
}
