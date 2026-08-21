package com.lengbot.service;

import com.lengbot.vo.KnowledgeAdvisorSummaryVO;
import com.lengbot.vo.LowRatedChunkVO;
import com.lengbot.vo.SleepingChunkVO;

import java.util.List;

/**
 * 知识库 Advisor 服务：基于用户反馈聚合给出知识库调优建议
 * <p>三类信号：
 * <ul>
 *   <li>反馈概览：整体点赞率、引用规模</li>
 *   <li>低分分块：点踩较多的分块，需复核内容质量</li>
 *   <li>休眠分块：长期未被检索命中的分块，可能需重切分或重向量化</li>
 * </ul>
 * 数据来源：message.metadata.ragReferences 关联 message_feedback</p>
 *
 * @author lw
 * @since 2026-07-20
 */
public interface KnowledgeAdvisorService {

    /**
     * 反馈聚合概览
     *
     * @param knowledgeId 知识库ID
     * @param windowDays  休眠判定窗口天数（用于统计休眠分块数）
     * @return 概览数据
     */
    KnowledgeAdvisorSummaryVO getSummary(Long knowledgeId, int windowDays);

    /**
     * 低分分块列表（按点踩数倒序）
     *
     * @param knowledgeId 知识库ID
     * @param limit       最大返回数
     * @return 低分分块列表
     */
    List<LowRatedChunkVO> getLowRatedChunks(Long knowledgeId, int limit);

    /**
     * 休眠分块列表（最近 days 天内未被引用）
     *
     * @param knowledgeId 知识库ID
     * @param days        休眠阈值天数
     * @param limit       最大返回数
     * @return 休眠分块列表
     */
    List<SleepingChunkVO> getSleepingChunks(Long knowledgeId, int days, int limit);
}
