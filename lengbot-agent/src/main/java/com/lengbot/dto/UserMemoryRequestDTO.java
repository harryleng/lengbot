package com.lengbot.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户长期记忆创建/更新请求
 *
 * @author finch
 * @since 2026-07-09
 */
@Data
public class UserMemoryRequestDTO {

    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 1000, message = "记忆内容不超过1000字")
    private String content;

    private String memoryType;

    private List<String> keywords;

    @DecimalMin(value = "0.0", message = "置信度最小为0")
    @DecimalMax(value = "1.0", message = "置信度最大为1")
    private BigDecimal confidence;

    private Long agentId;
}
