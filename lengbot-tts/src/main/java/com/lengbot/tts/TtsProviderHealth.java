package com.lengbot.tts;

import lombok.Data;

/**
 * TTS Provider 连通性自检结果。
 * <p>
 * 由 {@link TtsProvider#health()} 返回，描述该引擎当前是否可用及详细信息
 * （如 edge-tts 的令牌获取结果），便于运维/前端在用户环境快速定位「为何不出声」。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
public class TtsProviderHealth {

    /** 是否可用（如 edge-tts 能否取到令牌 / mock 恒为 true） */
    private boolean available;

    /** 可读的详细说明（成功原因或失败异常信息） */
    private String detail;
}
