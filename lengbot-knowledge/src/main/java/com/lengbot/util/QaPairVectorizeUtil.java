package com.lengbot.util;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lengbot.entity.QaPair;
import com.lengbot.enums.QaPairStatus;
import com.lengbot.mapper.EmbeddingMapper;
import com.lengbot.mapper.QaPairMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.lengbot.service.TextEmbeddingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问答对向量化辅助类（独立事务，避免向量化失败污染批量导入事务）
 *
 * @author lw
 * @since 2026-05-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaPairVectorizeUtil extends ServiceImpl<QaPairMapper, QaPair> {

    private final EmbeddingMapper embeddingMapper;
    /** 文本向量生成服务（AgentScope 引擎） */
    private final TextEmbeddingService textEmbeddingService;

    /**
     * 执行向量化（独立事务，失败不影响外部事务）
     *
     * @param qaPairId 问答对ID
     * @param question 问题文本
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doVectorizeInNewTransaction(Long qaPairId, String question) {
        QaPair qaPair = getById(qaPairId);
        if (qaPair == null) return;

        // 1. 更新状态为向量化中
        qaPair.setStatus(QaPairStatus.VECTORIZING);
        updateById(qaPair);

        // 2. 调用 embedding service
        double[] vector = textEmbeddingService.embed(question);

        // 3. 存储向量
        long id = IdWorker.getId();
        embeddingMapper.insertQaPairVector(id, qaPairId, "default", vector.length, toVectorString(vector));

        // 4. 更新状态为生效
        qaPair.setStatus(QaPairStatus.ACTIVE);
        updateById(qaPair);

        log.info("[QaPair] 向量化完成: qaPairId={}", qaPairId);
    }

    /**
     * 标记向量化失败（独立事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long qaPairId, String error) {
        QaPair qaPair = getById(qaPairId);
        if (qaPair != null) {
            qaPair.setStatus(QaPairStatus.FAILED);
            updateById(qaPair);
        }
        log.error("[QaPair] 向量化失败: qaPairId={}, error={}", qaPairId, error);
    }

    private String toVectorString(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
