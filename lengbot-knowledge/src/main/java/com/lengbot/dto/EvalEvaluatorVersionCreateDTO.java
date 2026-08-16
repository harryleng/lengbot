package com.lengbot.dto;

import lombok.Data;

/**
 * 评估器版本创建请求
 *
 * @author finch
 * @since 2026-05-27
 */
@Data
public class EvalEvaluatorVersionCreateDTO {

    private Long evaluatorId;

    private String version;

    private String prompt;

    private String variables;

    private String modelConfig;
}
