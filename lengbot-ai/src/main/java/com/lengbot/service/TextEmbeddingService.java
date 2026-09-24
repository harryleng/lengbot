package com.lengbot.service;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 文本嵌入服务（AgentScope 引擎）
 * <p>替代 Spring AI 的自动配置 EmbeddingModel，统一管理嵌入向量的生成调用。</p>
 * <p>注意：本接口负责“向量生成”，与知识库模块的
 * {@code com.lengbot.service.EmbeddingService}（负责“向量存储与相似度检索”）职责不同，请勿混用。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
public interface TextEmbeddingService {

    Logger log = LoggerFactory.getLogger(TextEmbeddingService.class);

    /**
     * 获取 AgentScope EmbeddingModel 实例
     *
     * @return EmbeddingModel 实例
     */
    EmbeddingModel getEmbeddingModel();

    /**
     * 文本嵌入（同步）
     *
     * @param text 待嵌入的文本
     * @return 嵌入向量（double[]）
     */
    default double[] embed(String text) {
        EmbeddingModel model = getEmbeddingModel();
        TextBlock block = TextBlock.builder().text(text).build();
        return model.embed(block).block();
    }

    /**
     * 批量文本嵌入（同步）
     *
     * @param texts 待嵌入的文本列表
     * @return 嵌入向量列表
     */
    /**
     * 批量文本嵌入（同步）。
     * <p>逐条隔离：单条文本嵌入失败（如超长触发 400）时记录日志并返回占位零向量，
     * 避免一个坏 chunk 拖垮整批、导致整篇文档入库中断。</p>
     */
    default List<double[]> embedBatch(List<String> texts) {
        EmbeddingModel model = getEmbeddingModel();
        int dims = getDimensions();
        return texts.stream()
                .map(text -> {
                    try {
                        return model.embed(TextBlock.builder().text(text).build()).block();
                    } catch (Exception e) {
                        log.warn("[Embedding] 单条文本嵌入失败，已跳过并返回零向量占位: 长度={}, 预览={}, error={}",
                                (text == null ? 0 : text.length()),
                                (text == null ? "" : text.substring(0, Math.min(100, text.length()))),
                                e.getMessage());
                        return new double[dims];
                    }
                })
                .toList();
    }

    /**
     * 文本嵌入（异步）
     *
     * @param text 待嵌入的文本
     * @return Mono&lt;double[]&gt;
     */
    default Mono<double[]> embedAsync(String text) {
        EmbeddingModel model = getEmbeddingModel();
        return model.embed(TextBlock.builder().text(text).build());
    }

    /**
     * 获取嵌入向量维度
     *
     * @return 维度
     */
    default int getDimensions() {
        return getEmbeddingModel().getDimensions();
    }

    /**
     * 获取模型名称
     *
     * @return 模型名称
     */
    default String getModelName() {
        return getEmbeddingModel().getModelName();
    }
}
