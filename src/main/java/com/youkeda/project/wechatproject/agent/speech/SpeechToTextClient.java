package com.youkeda.project.wechatproject.agent.speech;

import java.io.IOException;

/**
 * 语音识别（STT）接口。
 * 用户实现此接口接入自己的ASR大模型。
 */
public interface SpeechToTextClient {

    /**
     * 将语音转为文字。
     *
     * @param audioBytes 语音原始bytes
     * @param format     编码格式（如 "silk", "wav", "mp3"）
     * @return 识别出的文字
     */
    String recognize(byte[] audioBytes, String format) throws IOException;
}
