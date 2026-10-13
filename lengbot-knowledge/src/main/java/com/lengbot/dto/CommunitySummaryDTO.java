package com.lengbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 社区摘要生成请求（GraphRAG 社区总结链路 · 第 2 步）
 *
 * @author lw
 * @since 2026-10-10
 */
@Data
@Schema(description = "社区摘要生成请求")
public class CommunitySummaryDTO {

    @Schema(description = "模型提供商ID（为空使用系统默认）")
    private Long providerId;

    @Schema(description = "指定模型ID（为空使用 provider 默认模型）")
    private String modelId;

    @Schema(description = "并发数（1-32，默认4）")
    private Integer concurrency;

    @Schema(description = "单个社区最多送给 LLM 的关系条数（默认250）")
    private Integer maxTriples;

    @Schema(description = "单个社区最多送给 LLM 的实体个数（默认120）")
    private Integer maxEntities;

    @Schema(description = "模型参数 JSON（如 temperature、maxTokens）")
    private Map<String, Object> modelParams;
}
