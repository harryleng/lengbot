package com.lengbot.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TTS 模块配置（前缀 {@code lengbot.tts}）。
 * <p>
 * 示例（application.yml）：
 * <pre>
 * lengbot:
 *   tts:
 *     provider: edge-tts          # 当前启用的 Provider：mock | edge-tts
 *     default-voice: zh-CN-XiaoxiaoNeural
 *     default-rate: "+0%"
 *     default-pitch: "+0Hz"
 *     format: audio-24khz-48kbitrate-mono-mp3
 *     max-text-length: 2000
 *     edge-tts:
 *       host: speech.platform.bing.com
 *       trusted-client-token: 6A5AA1D4EAFF4E9FB37E23D68491D6F4
 *       websocket-path: /consumer/speech/synthesize/readaloud/edge/v1
 *       voices-path: /consumer/speech/synthesize/readaloud/voices/list
 *       chromium-full-version: 143.0.3650.75
 *       connect-timeout-ms: 10000
 *       read-timeout-ms: 30000
 * </pre>
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "lengbot.tts")
public class TtsProperties {

    /** 当前启用的 Provider 名称（对应 TtsProvider#name） */
    private String provider = "edge-tts";

    /** 默认音色名（voice 为 null 时使用） */
    private String defaultVoice = "zh-CN-XiaoxiaoNeural";

    /** 默认语速（edge-tts 风格字符串，如 +0% / -50% / +100%） */
    private String defaultRate = "+0%";

    /** 默认音调（edge-tts 风格字符串，如 +0Hz / +10Hz） */
    private String defaultPitch = "+0Hz";

    /** 默认输出格式（微软在线 TTS 的 outputFormat 取值） */
    private String format = "audio-24khz-48kbitrate-mono-mp3";

    /** 单次合成文本最大长度（防滥用） */
    private int maxTextLength = 2000;

    /** EdgeTTS（微软在线 TTS）相关配置 */
    private EdgeTts edgeTts = new EdgeTts();

    /** Mock Provider 配置（生成测试用音频） */
    private Mock mock = new Mock();

    @Data
    public static class EdgeTts {
        /** 微软 Edge TTS 服务主机（当前为 speech.platform.bing.com） */
        private String host = "speech.platform.bing.com";

        /** 可信客户端令牌（TRUSTED_CLIENT_TOKEN）：既作为 URL 查询参数，也参与本地 Sec-MS-GEC 计算 */
        private String trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

        /** WebSocket 路径（不含 host），Sec-MS-GEC / ConnectionId 等以查询参数附加 */
        private String websocketPath = "/consumer/speech/synthesize/readaloud/edge/v1";

        /** 音色列表路径（不含 host），trustedclienttoken 以查询参数附加 */
        private String voicesPath = "/consumer/speech/synthesize/readaloud/voices/list";

        /** Sec-MS-GEC 版本（Chromium 完整版本号），最终写为 1-<version> */
        private String chromiumFullVersion = "143.0.3650.75";

        /** 自定义 User-Agent（复刻 Edge 浏览器指纹） */
        private String userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

        /** Origin（Edge 阅读 aloud 扩展固定值） */
        private String origin = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

        /** 建连超时（ms） */
        private int connectTimeoutMs = 10000;

        /** 读取超时（ms） */
        private int readTimeoutMs = 30000;

        /** Sec-MS-GEC 令牌缓存有效期（s），5 分钟窗口本身即限制其有效期 */
        private int tokenTtlSeconds = 300;
    }

    @Data
    public static class Mock {
        /** 采样率（Hz） */
        private int sampleRate = 24000;

        /** 生成音频时长（ms） */
        private int durationMs = 300;

        /** 提示音频率（Hz） */
        private int frequencyHz = 440;
    }
}
