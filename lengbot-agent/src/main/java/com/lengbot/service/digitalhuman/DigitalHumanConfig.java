package com.lengbot.service.digitalhuman;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 数字人配置（解析 agent.config 中的 digitalHuman 段）。
 *
 * <p>示例 agent.config：
 * <pre>
 * {
 *   "digitalHuman": {
 *     "portraitUrl": "https://.../portrait.png",
 *     "engine": "placeholder",
 *     "voice": { "speed": 1.0 }
 *   }
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DigitalHumanConfig {

    /** 形象图 URL（上传到 MinIO 的人物肖像） */
    private String portraitUrl;

    /** 驱动引擎：placeholder=轻量占位版（前端口型动画）；后续可扩展 commercial / local 等 */
    private String engine = "placeholder";

    /** 音色 / 语速等可选参数（预留，对接真实 TTS / 数字人引擎时使用） */
    private Map<String, Object> voice;

    @SuppressWarnings("unchecked")
    public static DigitalHumanConfig fromMap(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        DigitalHumanConfig cfg = new DigitalHumanConfig();
        cfg.setPortraitUrl((String) m.get("portraitUrl"));
        cfg.setEngine((String) m.getOrDefault("engine", "placeholder"));
        cfg.setVoice((Map<String, Object>) m.get("voice"));
        return cfg;
    }

    /** 是否已配置可出镜的形象图 */
    public boolean isConfigured() {
        return portraitUrl != null && !portraitUrl.isBlank();
    }
}
