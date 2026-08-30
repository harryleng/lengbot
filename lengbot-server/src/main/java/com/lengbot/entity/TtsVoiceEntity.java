package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * TTS 音色管理表。
 * <p>
 * 缓存各 Provider（如 edge-tts）返回的音色清单，并在此之上叠加「收藏 / 分组 / 备注」
 * 等本地管理字段，使音色列表可离线使用、可按需整理，而不必每次实时拉取 Provider。
 * 同步时仅更新 Provider 派生字段（展示名 / 语言 / 性别），保留用户的管理字段。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Data
@TableName("tts_voice")
@Schema(description = "TTS 音色管理表（缓存 Provider 音色 + 收藏/分组/备注）")
public class TtsVoiceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("voice_name")
    @Schema(description = "音色名（Provider 的 ShortName，如 zh-CN-XiaoxiaoNeural）")
    private String voiceName;

    @TableField("provider")
    @Schema(description = "所属 Provider（如 edge-tts）")
    private String provider;

    @TableField("friendly_name")
    @Schema(description = "展示名（如 晓晓(女声)）")
    private String friendlyName;

    @TableField("locale")
    @Schema(description = "语言区域，如 zh-CN")
    private String locale;

    @TableField("gender")
    @Schema(description = "性别 Male / Female")
    private String gender;

    @TableField("favorite")
    @Schema(description = "是否收藏 0/1（收藏的音色置顶优先展示）")
    private Integer favorite;

    @TableField("voice_group")
    @Schema(description = "自定义分组（自由文本，可为空；用于把常用音色归类）")
    private String voiceGroup;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @TableField("sort_order")
    @Schema(description = "手动排序权重，越小越靠前")
    private Integer sortOrder;

    @TableField(value = "extra_json", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "扩展信息（JSON，预留）")
    private Map<String, Object> extraJson;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableField("deleted")
    @TableLogic
    @Schema(description = "逻辑删除标记")
    private Integer deleted;
}
