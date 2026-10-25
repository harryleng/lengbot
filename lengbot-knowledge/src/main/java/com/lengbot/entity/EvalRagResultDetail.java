package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * RAG 评估结果详情表
 *
 * @author lw
 * @since 2026-05-28
 */
@Data
@TableName("eval_rag_result_detail")
@Schema(description = "RAG 评估结果详情表")
public class EvalRagResultDetail {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("result_id")
    @Schema(description = "评估结果ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resultId;

    @TableField("query")
    @Schema(description = "评估问题")
    private String query;

    @TableField("gold_chunk_ids")
    @Schema(description = "标准片段ID列表（JSON数组）")
    private String goldChunkIds;

    @TableField("gold_answer")
    @Schema(description = "标准答案")
    private String goldAnswer;

    @TableField("generated_answer")
    @Schema(description = "AI生成的答案")
    private String generatedAnswer;

    @TableField("retrieved_chunk_ids")
    @Schema(description = "检索到的片段ID列表（JSON数组）")
    private String retrievedChunkIds;

    @TableField("retrieval_scores")
    @Schema(description = "检索指标JSON")
    private String retrievalScores;

    @TableField("answer_score")
    @Schema(description = "答案评分（0.0 或 1.0）")
    private Double answerScore;

    @TableField("answer_reasoning")
    @Schema(description = "评分理由")
    private String answerReasoning;

    @TableField("faithfulness_score")
    @Schema(description = "Faithfulness 接地性得分（0~1，越高越无幻觉）")
    private Double faithfulnessScore;

    @TableField("faithfulness_reasoning")
    @Schema(description = "Faithfulness 判定摘要")
    private String faithfulnessReasoning;

    @TableField("answer_relevancy_score")
    @Schema(description = "Answer-Relevancy 答案相关性得分（0~1，越高越切题，抓跑题/答非所问）")
    private Double answerRelevancyScore;

    @TableField("answer_relevancy_reasoning")
    @Schema(description = "Answer-Relevancy 判定摘要")
    private String answerRelevancyReasoning;

    @TableField("sort_order")
    @Schema(description = "排序")
    private Integer sortOrder;
}
