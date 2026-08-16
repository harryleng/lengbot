package com.lengbot.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * LLM 评分结果
 *
 * @author finch
 * @since 2026-05-27
 */
@Data
public class EvalScoreResultDTO {

    private BigDecimal score;

    private String reason;
}
