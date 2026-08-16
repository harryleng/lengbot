package com.lengbot.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Dify Dataset 连通性测试结果。 */
@Data
@Schema(description = "Dify Dataset 连通性测试结果")
public class DifyConnectionTestVO {

    @Schema(description = "是否连接成功")
    private Boolean connected;

    @Schema(description = "验证使用的 Dataset ID")
    private String datasetId;
}
