package com.lengbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Dify Dataset 连接配置。
 * Token 仅用于写入加密凭证表，绝不回传到知识库配置。
 */
@Data
@Schema(description = "Dify Dataset 连接配置")
public class DifyKnowledgeConfigDTO {

    @Size(max = 512, message = "Dify API 地址不能超过512字符")
    @Schema(description = "Dify API 地址，必须以 /v1 结尾", example = "https://dify.example.com/v1")
    private String apiUrl;

    @Size(max = 128, message = "Dify Dataset ID不能超过128字符")
    @Schema(description = "Dify Dataset ID")
    private String datasetId;

    @Size(max = 2048, message = "Dify Token不能超过2048字符")
    @Schema(description = "Dify Dataset API Token，仅写入时使用，不会回显")
    private String token;
}
