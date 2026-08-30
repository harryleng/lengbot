package com.lengbot.tts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可用的 TTS 音色（供前端下拉选择）。
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TtsVoice {

    /** 音色名（传给 Provider 的 voice 参数），如 zh-CN-XiaoxiaoNeural */
    private String name;

    /** 展示名，如 晓晓(女声) */
    private String friendlyName;

    /** 语言区域，如 zh-CN */
    private String locale;

    /** 性别 Male / Female，可空 */
    private String gender;

    /** 所属 Provider（便于前端区分来源） */
    private String provider;
}
