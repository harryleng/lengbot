package com.lengbot.tts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TTS 合成结果：音频字节 + 内容类型。
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TtsAudio {

    /** 音频字节（mp3 / wav 等，由 contentType 决定） */
    private byte[] data;

    /**
     * HTTP Content-Type，如 {@code audio/mpeg}、{@code audio/wav}。
     * Controller 据此设置响应头。
     */
    private String contentType;

    /** 音频格式标识（与请求 format 对应，便于前端区分容器类型） */
    private String format;
}
