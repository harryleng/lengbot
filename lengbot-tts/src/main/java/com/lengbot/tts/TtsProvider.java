package com.lengbot.tts;

import java.util.List;

/**
 * TTS Provider 可插拔接口。
 * <p>
 * 每种合成引擎（Mock / EdgeTTS / 未来可扩展的 Azure / 讯飞 / 本地引擎）实现本接口，
 * 由 {@link TtsProviderFactory} 按配置 {@code lengbot.tts.provider} 选择其一。
 * 新增引擎只需：① 实现本接口；② 在 factory 中登记；③ 在 application.yml 增加对应配置段。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
public interface TtsProvider {

    /**
     * 合成音频。
     *
     * @param request 合成请求（voice/rate/pitch/format 为 null 时由实现自行决定兜底）
     * @return 音频结果
     * @throws TtsException 合成失败（网络/协议/参数等）
     */
    TtsAudio synthesize(TtsRequest request);

    /**
     * 列出本 Provider 支持的全部音色。
     *
     * @return 音色列表（不可为 null，至少返回空列表）
     */
    List<TtsVoice> listVoices();

    /**
     * Provider 标识（与配置 {@code lengbot.tts.provider} 对应，如 {@code mock} / {@code edge-tts}）。
     */
    String name();

    /**
     * 当前是否可用（用于启动时健康探测与 factory 选择兜底）。
     * 例如 EdgeTTS 在网络不可达时应返回 false。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 连通性自检：探测该引擎当前是否真的可用，并返回可读说明。
     * 默认实现基于 {@link #isAvailable()} 返回「ok」；具体引擎（如 EdgeTTS）应覆写以执行真实探测
     * （如尝试获取令牌），便于在用户环境快速定位「为何不出声」。
     *
     * @return 自检结果（available + detail）
     */
    default TtsProviderHealth health() {
        TtsProviderHealth h = new TtsProviderHealth();
        h.setAvailable(isAvailable());
        h.setDetail("ok");
        return h;
    }
}
