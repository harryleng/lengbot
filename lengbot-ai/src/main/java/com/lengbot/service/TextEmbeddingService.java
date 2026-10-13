package com.lengbot.service;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

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
    AtomicLong EMBED_FAIL_TOTAL = new AtomicLong(0);

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
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel model = getEmbeddingModel();
        int maxRetries = 3;
        List<double[]> result = new ArrayList<>(texts.size());
        int failedInBatch = 0;
        for (String text : texts) {
            double[] vec = null;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= maxRetries && vec == null; attempt++) {
                try {
                    vec = model.embed(TextBlock.builder().text(text).build()).block();
                } catch (Exception e) {
                    lastErr = e;
                    if (attempt < maxRetries) {
                        log.warn("[Embedding] 第{}次嵌入失败, 将重试: 长度={}, error={}", attempt,
                                (text == null ? 0 : text.length()), e.getMessage());
                        try {
                            Thread.sleep(150L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (vec == null) {
                failedInBatch++;
                EMBED_FAIL_TOTAL.incrementAndGet();
                log.error("[Embedding] 单条文本嵌入最终失败, 已隔离(不写零向量, 调用方须跳过该条): 长度={}, 预览={}, error={}",
                        (text == null ? 0 : text.length()),
                        (text == null ? "" : text.substring(0, Math.min(100, text.length()))),
                        lastErr == null ? "未知" : lastErr.getMessage());
                result.add(null);
            } else {
                result.add(vec);
            }
        }
        if (failedInBatch > 0) {
            double rate = (double) failedInBatch / texts.size();
            if (rate >= 0.5) {
                log.error("[Embedding] 批量嵌入失败率过高: {}/{} ({}%), 请检查 embedding 服务健康度/配额/网络",
                        failedInBatch, texts.size(), (int) (rate * 100));
            } else {
                log.warn("[Embedding] 批量嵌入部分失败: {}/{}", failedInBatch, texts.size());
            }
        }
        return result;
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
