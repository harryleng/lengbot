package com.lengbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lengbot.enums.AuthType;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.ToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Tool 请求DTO
 *
 * @author finch
 * @since 2026-05-20
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolRequestDTO {

    private Long id;

    @NotBlank(message = "工具标识不能为空")
    @Size(max = 30, message = "工具标识不超过30字")
    private String name;

    @Size(max = 30, message = "显示名称不超过30字")
    private String displayName;

    /** 图标标识（Ant Design 图标组件名，如 GlobalOutlined） */
    @Size(max = 64, message = "图标标识不超过64字")
    private String icon;

    @Size(max = 50, message = "工具描述不超过50字")
    private String description;

    @NotNull(message = "工具类型不能为空")
    private ToolType toolType;

    private String inputSchema;

    private String outputSchema;

    private String outputExample;

    private String config;

    private String endpointUrl;

    private AuthType authType;

    private String authConfig;

    /** 工具标签（JSON数组字符串） */
    private String tags;

    /** 是否启用限流：true 时按 rateLimitConfig 在 (userId, toolName) 维度限流 */
    private Boolean rateLimitEnabled;

    /** 限流配置 JSON：{"limit":10,"window":"MINUTE|HOUR|DAY"} */
    private String rateLimitConfig;

    private CommonStatus status;
}
