package com.lengbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * SubAgent 请求 DTO
 *
 * @author lw
 * @since 2026-05-24
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "SubAgent请求")
public class SubAgentRequestDTO {

    @Schema(description = "ID（更新时必填）")
    private Long id;

    @NotBlank(message = "名称不能为空")
    @Size(max = 30, message = "标识名称不超过30字")
    @Schema(description = "唯一标识（英文）")
    private String name;

    @NotBlank(message = "显示名称不能为空")
    @Size(max = 30, message = "显示名称不超过30字")
    @Schema(description = "显示名称（中文）")
    private String displayName;

    @Size(max = 64, message = "图标标识不超过64字")
    @Schema(description = "图标标识（Ant Design 图标组件名，如 RobotOutlined）")
    private String icon;

    @NotBlank(message = "描述不能为空")
    @Size(max = 50, message = "子智能体描述不超过50字")
    @Schema(description = "子智能体描述")
    private String description;

    @NotBlank(message = "系统提示词不能为空")
    @Size(max = 2000, message = "系统提示词不超过2000字")
    @Schema(description = "系统提示词")
    private String systemPrompt;

    @Schema(description = "绑定工具ID列表")
    private List<String> toolIds;

    @Schema(description = "可选的 Provider ID 覆盖，null 表示继承主 Agent")
    private Long providerId;

    @Schema(description = "可选的模型名称覆盖（如 gpt-4o）")
    private String llmModel;

    @Schema(description = "模型连接超时（秒），默认 10")
    private Integer connectTimeoutSeconds;

    @Schema(description = "整体响应超时（秒），默认 45")
    private Integer readTimeoutSeconds;

    @Schema(description = "模型调用失败重试次数，默认 1")
    private Integer modelRetryTimes;

    /** @deprecated 兼容旧字段，等同于 providerId */
    @Schema(description = "可选的 Provider ID（兼容旧字段）")
    private Long modelId;

    @Schema(description = "是否启用")
    private Boolean enabled;

    /** 解析有效的 Provider ID：优先 providerId，fallback modelId */
    public Long resolveProviderId() {
        return providerId != null ? providerId : modelId;
    }
}