package com.lengbot.tts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TTS 合成请求。
 * <p>
 * 单条文本合成请求：文本、音色名、语速、音调、输出格式。
 * 字段均可为 null —— 为 null 时由 {@link TtsService} 用 {@link TtsProperties} 中的默认值补齐。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TtsRequest {

    /** 待合成文本（必填） */
    private String text;

    /**
     * 音色名（如 {@code zh-CN-XiaoxiaoNeural}）。
     * 对应 edge-tts 的 {@code <voice name>}；为 null 时使用默认音色。
     */
    private String voice;

    /**
     * 语速。两种形态：
     * <ul>
     *   <li>数字（相对倍数，1.0 = 正常）：会被 Provider 转换为 {@code +X%}</li>
     *   <li>字符串（如 {@code +0%} / {@code -50%}）：原样透传</li>
     * </ul>
     */
    private Object rate;

    /**
     * 音调。两种形态：
     * <ul>
     *   <li>数字（相对基准，1.0 = 正常）：会被 Provider 转换为 {@code +XHz}</li>
     *   <li>字符串（如 {@code +0Hz}）：原样透传</li>
     * </ul>
     */
    private Object pitch;

    /** 输出格式（如 {@code audio-24khz-48kbitrate-mono-mp3}），为 null 时使用默认格式 */
    private String format;

    /**
     * 可选：指定本次合成使用的 Provider 名称（覆盖当前全局生效的引擎）。
     * 为 null 或空时使用 {@code TtsService} 当前生效的 Provider。
     * 便于在不改变全局状态的前提下，针对单次请求测试某个具体引擎。
     */
    private String provider;
}
