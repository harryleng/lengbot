package com.lengbot.dto;

import lombok.Data;

/**
 * 评测集版本创建请求
 *
 * @author finch
 * @since 2026-05-27
 */
@Data
public class EvalDatasetVersionCreateDTO {

    private Long datasetId;

    private String version;
}
