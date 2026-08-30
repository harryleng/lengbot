package com.lengbot.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * TTS 音色视图（对外返回），在 {@code TtsVoice} 基础上叠加本地管理字段。
 * <p>
 * 其中 {@code voiceURI} 直接等于 {@code voiceName}，用以兼容前端音色下拉（历史字段名），
 * 前端既可用 {@code voiceURI} 也可用 {@code name} 作为音色标识。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@Schema(description = "TTS 音色视图（含管理字段）")
public class TtsVoiceView {

    @Schema(description = "音色标识（= voiceName，兼容前端下拉）")
    private String voiceURI;

    @Schema(description = "音色名（Provider ShortName）")
    private String name;

    @Schema(description = "展示名")
    private String friendlyName;

    @Schema(description = "语言区域")
    private String locale;

    @Schema(description = "性别")
    private String gender;

    @Schema(description = "所属 Provider")
    private String provider;

    @Schema(description = "是否收藏")
    private Boolean favorite;

    @Schema(description = "自定义分组")
    private String voiceGroup;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "排序权重")
    private Integer sortOrder;
}
