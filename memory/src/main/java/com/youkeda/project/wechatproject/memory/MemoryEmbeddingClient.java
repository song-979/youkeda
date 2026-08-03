package com.youkeda.project.wechatproject.memory;

import java.io.IOException;

public interface MemoryEmbeddingClient {
    double[] embed(String text) throws IOException;
}
