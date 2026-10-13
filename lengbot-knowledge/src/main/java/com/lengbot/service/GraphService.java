package com.lengbot.service;

import com.lengbot.dto.*;
import com.lengbot.vo.*;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务接口
 *
 * @author lw
 * @since 2026-05-29
 */
public interface GraphService {

    /**
     * 触发文档的图谱抽取（异步任务）
     *
     * @param knowledgeId 知识库ID
     * @param request     抽取配置请求
     * @return 任务ID
     */
    Long extractFromDocument(Long knowledgeId, GraphExtractDTO request);

    /**
     * 自动触发图谱抽取（内部调用，跳过权限校验）
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID
     * @return 任务ID，null 表示跳过（Neo4j 不可用或知识库不存在）
     */
    Long autoExtractFromDocument(Long knowledgeId, Long documentId);

    /**
     * 批量导入三元组到图谱
     *
     * @param knowledgeId 知识库ID
     * @param triples     三元组列表
     * @param providerId  模型提供商ID（用于实体 Embedding）
     * @return 导入结果统计
     */
    GraphStatsVO importTriples(Long knowledgeId, List<GraphTripleDTO> triples, Long providerId);

    /**
     * 获取子图（用于可视化和检索）
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID（为null表示全库图谱）
     * @param keyword     搜索关键词（null 表示采样高连接度节点）
     * @param maxDepth    最大跳数（默认2）
     * @param maxNodes    最大节点数（默认50）
     * @return 子图数据
     */
    GraphSubgraphVO getSubgraph(Long knowledgeId, Long documentId, String keyword, int maxDepth, int maxNodes);

    /**
     * 语义搜索图谱节点（基于 Milvus 向量相似度）
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID（为null表示全库图谱）
     * @param query       搜索查询
     * @param topK        返回数量上限
     * @param minScore    相似度阈值（0~1，null 时使用默认值）
     * @param providerId  模型提供商ID（预留，当前使用系统默认 Embedding）
     * @return 匹配的节点列表（含相似度 score）
     */
    List<GraphNodeVO> semanticSearch(Long knowledgeId, Long documentId, String query, int topK, Double minScore, Long providerId);

    /**
     * 图谱检索：从问题中提取实体，展开子图，返回文本格式的三元组
     *
     * @param knowledgeId 知识库ID
     * @param question    用户问题
     * @param providerId  模型提供商ID
     * @return 文本三元组列表，如 ["张三 担任 技术总监", ...]
     */
    List<String> searchForRag(Long knowledgeId, String question, Long providerId);

    /**
     * 获取图谱统计信息
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID（为null表示全库统计）
     * @return 统计信息
     */
    GraphStatsVO getStats(Long knowledgeId, Long documentId);

    /**
     * 批量查询哪些文档已有图谱数据
     *
     * @param knowledgeId 知识库ID
     * @param documentIds 文档ID列表
     * @return 已有图谱的文档ID集合
     */
    List<Long> getExistingDocIds(Long knowledgeId, List<Long> documentIds);

    /**
     * 删除知识库的全部图谱数据
     *
     * @param knowledgeId 知识库ID
     */
    void deleteByKnowledgeId(Long knowledgeId);

    /**
     * 删除知识库的全部图谱数据（内部调用，跳过权限校验）
     *
     * @param knowledgeId 知识库ID
     */
    void deleteByKnowledgeIdInternal(Long knowledgeId);

    /**
     * 删除文档关联的图谱数据
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID
     */
    void deleteByDocumentId(Long knowledgeId, Long documentId);

    /**
     * 删除文档关联的图谱数据（内部调用，跳过权限校验）
     *
     * @param knowledgeId 知识库ID
     * @param documentId  文档ID
     */
    void deleteByDocumentIdInternal(Long knowledgeId, Long documentId);

    /**
     * 手动创建节点
     *
     * @param knowledgeId 知识库ID
     * @param name        节点名称
     * @param entityType  实体类型
     * @param description 描述
     * @return 创建的节点
     */
    GraphNodeVO createNode(Long knowledgeId, String name, String entityType, String description);

    /**
     * 手动创建边
     *
     * @param knowledgeId 知识库ID
     * @param headName    起始节点名称
     * @param relationType 关系类型
     * @param tailName    目标节点名称
     * @param description 描述
     * @return 创建的边
     */
    GraphEdgeVO createEdge(Long knowledgeId, String headName, String relationType, String tailName, String description);

    /**
     * 删除节点（级联删除关联边）
     *
     * @param knowledgeId 知识库ID
     * @param elementId   节点 elementId
     */
    void deleteNode(Long knowledgeId, String elementId);

    /**
     * 删除边
     *
     * @param knowledgeId 知识库ID
     * @param elementId   边 elementId
     */
    void deleteEdge(Long knowledgeId, String elementId);

    /**
     * 触发社区检测（GraphRAG 社区总结链路 · 第 1 步）
     * <p>对已完成图谱抽取的知识库做弱连通分量分群，为实体写回 community_id 并生成 Community 节点，
     * 供后续的社区摘要生成与全局检索消费。
     * <p>纯图算法计算，不消耗 LLM token；异步执行，返回任务ID供轮询进度。
     *
     * @param knowledgeId 知识库ID
     * @return 社区检测任务ID
     */
    Long detectCommunities(Long knowledgeId);

    /**
     * 触发社区摘要生成（GraphRAG 社区总结链路 · 第 2 步）
     * <p>读取社区检测产出的 Community 节点，用 LLM 为每个社区提炼一段高层语义摘要，
     * 回写到 Community.summary。
     * <p>前置依赖：必须先执行 {@link #detectCommunities(Long)}，否则无社区可摘要。
     * <p>会消耗 LLM token（每个社区一次调用）；异步执行，返回任务ID供轮询进度。
     *
     * @param knowledgeId 知识库ID
     * @param request     摘要配置（模型提供商、并发度、单社区上下文上限等），可为 null 走默认
     * @return 社区摘要任务ID
     */
    Long summarizeCommunities(Long knowledgeId, CommunitySummaryDTO request);
}
