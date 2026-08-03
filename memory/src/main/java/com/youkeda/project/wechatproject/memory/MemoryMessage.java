package com.youkeda.project.wechatproject.memory;

import java.util.Objects;

public class MemoryMessage {

    private final String role;
    private final Object content;

    public MemoryMessage(String role, Object content) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public Object getContent() {
        return content;
    }
}
