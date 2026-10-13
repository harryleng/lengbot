package com.lengbot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务类型
 *
 * @author lw
 * @since 2026-05-21
 */
@Getter
@AllArgsConstructor
public enum TaskType implements EnumDisplay {

    DOCUMENT_UPLOAD("document_upload", "文档上传", "documentUploadExecutor", Group.DEFAULT),
    DOCUMENT_INGEST("document_ingest", "文档入库", "documentIngestExecutor", Group.DEFAULT),
    EXPERIMENT_RUN("experiment_run", "实验执行", "experimentRunExecutor", Group.DEFAULT),
    BENCHMARK_GENERATE("benchmark_generate", "基准生成", "benchmarkGenerateExecutor", Group.DEFAULT),
    BENCHMARK_IMPORT("benchmark_import", "基准导入", "benchmarkImportExecutor", Group.DEFAULT),
    RAG_EVALUATION("rag_evaluation", "RAG评估", "ragEvaluationExecutor", Group.DEFAULT),
    GRAPH_EXTRACTION("graph_extraction", "图谱抽取", "graphExtractionExecutor", Group.HEAVY),
    QA_PAIR_GENERATE("qa_pair_generate", "问答对生成", "qaPairGenerateExecutor", Group.HEAVY),

    /**
     * 社区检测：GraphRAG「社区总结」链路第 1 步。
     * 对已抽取好的图谱做连通分量分群，产出实体的 community_id 与 Community 节点，
     * 供后续的社区摘要生成与全局检索消费。
     */
    COMMUNITY_DETECT("community_detect", "社区检测", "communityDetectExecutor", Group.HEAVY),

    /**
     * 社区摘要生成：GraphRAG「社区总结」链路第 2 步。
     * 依赖第 1 步的社区检测结果，调用 LLM 为每个社区生成高层语义摘要。
     */
    COMMUNITY_SUMMARY("community_summary", "社区摘要", "communitySummaryExecutor", Group.HEAVY);

    /**
     * 任务分组：决定投递到哪个 Stream 消费组，实现长任务与短任务的隔离
     */
    public enum Group {
        /** 默认组：短任务，与 cg:default 消费者绑定 */
        DEFAULT,
        /** 重型组：长任务（图谱抽取、问答对生成），与 cg:heavy 消费者绑定，避免阻塞短任务 */
        HEAVY,
    }

    @EnumValue
    private final String code;

    private final String desc;

    private final String beanName;

    private final Group group;

    @JsonValue
    public String getDesc() {
        return desc;
    }

    @JsonCreator
    public static TaskType fromValue(String value) {
        for (TaskType e : values()) {
            if (e.code.equalsIgnoreCase(value) || e.desc.equals(value) || e.name().equalsIgnoreCase(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的任务类型: " + value);
    }
}
