package com.lengbot.service.digitalhuman;

import lombok.Data;

/**
 * 数字人驱动引擎接口。
 *
 * <p>轻量占位版由前端用「形象图 + 口型动画」驱动（不生成真实视频）；
 * 真实引擎（商业数字人 API 如 HeyGen / D-ID / 阿里云，或本地开源模型如
 * SadTalker / MuseTalk / LivePortrait）实现本接口，将文本 / 语音 + 形象图
 * 生成口型同步视频，返回可播放的视频 URL。
 *
 * <p>接入真实引擎时，由对话服务在 agent 为数字人型时调用
 * {@link #generate(DigitalHumanRequest)}，并将返回的 videoUrl 随消息推给前端播放。
 */
public interface DigitalHumanEngine {

    /** 引擎标识，对应 {@link DigitalHumanConfig#engine} */
    String name();

    /** 生成数字人视频（占位实现返回 frontendDriven=true、videoUrl=null） */
    DigitalHumanResult generate(DigitalHumanRequest request);

    @Data
    class DigitalHumanRequest {
        /** 待口型同步的文案（通常来自 LLM 文本回复） */
        private String text;
        /** 已合成的语音地址（可选，真实引擎可据此驱动口型） */
        private String audioUrl;
        /** 形象图地址 */
        private String portraitUrl;
        /** 引擎标识 */
        private String engine;
    }

    @Data
    class DigitalHumanResult {
        /** 生成的视频播放地址；占位实现为 null */
        private String videoUrl;
        /** true 表示由前端用形象图 + 口型动画驱动，无需后端视频 */
        private boolean frontendDriven;
    }
}
