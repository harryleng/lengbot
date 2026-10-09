package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lengbot.entity.Chunk;
import com.lengbot.entity.Document;
import com.lengbot.entity.Embedding;
import com.lengbot.entity.Knowledge;
import com.lengbot.enums.KnowledgeType;
import com.lengbot.mapper.DocumentMapper;
import com.lengbot.mapper.EmbeddingMapper;
import com.lengbot.service.ChunkService;
import com.lengbot.service.EmbeddingService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.util.GraphRetrievalUtil;
import com.lengbot.util.MilvusUtil;
import com.lengbot.util.RerankerUtil;
import com.lengbot.util.VectorUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量服务实现类：负责向量存储和相似度检索
 * <p>pgvector 相关 SQL 操作已下沉到 EmbeddingMapper（@Select/@Insert/@Delete 注解）</p>
 *
 * @author lw
 * @since 2026-05-19
 */
@Slf4j
@Service
public class EmbeddingServiceImpl extends ServiceImpl<EmbeddingMapper, Embedding>
        implements EmbeddingService {

    private final EmbeddingMapper embeddingMapper;
    private final MilvusUtil milvusUtil;
    private final DocumentMapper documentMapper;
    private final ChunkService chunkService;
    private final RerankerUtil rerankerUtil;
    private final GraphRetrievalUtil graphRetrievalUtil;
    /** 延迟解析：KnowledgeServiceImpl 反向依赖 EmbeddingService，ObjectProvider 取 bean 时打破构造期循环 */
    private final ObjectProvider<KnowledgeService> knowledgeServiceProvider;

    private static final String SEARCH_MODE_VECTOR = "vector";
    private static final String SEARCH_MODE_KEYWORD = "keyword";
    private static final String SEARCH_MODE_HYBRID = "hybrid";

    /** pgvector HNSW 默认 ef_search，与 sql/ 建索引时的 ef_construction 对齐 */
    private static final int DEFAULT_HNSW_EF_SEARCH = 100;
    /** Milvus HNSW 默认 ef（向量检索候选队列大小） */
    private static final int DEFAULT_MILVUS_SEARCH_EF = 100;
    /** PPR 迭代次数：>10 收敛稳定，过大延迟线性增长 */
    private static final int DEFAULT_PPR_ITERATIONS = 15;
    /** RRF 平滑常量：标准值 60，越小越偏向高排名项 */
    private static final int DEFAULT_RRF_K = 60;

    /** Milvus 集合存在性缓存，避免每次检索前 RPC 调用 hasCollection */
    private static final String METADATA_FILTER_KEY = "metadataFilter";
    private static final String METADATA_FILTER_JSON_KEY = "metadataFilterJson";
    private static final String METADATA_DOC_IDS_KEY = "metadataDocIds";
    private final ConcurrentHashMap<Long, Boolean> collectionExistsCache = new ConcurrentHashMap<>();

    /** 向量路由缓存：knowledgeId → 是否为 Milvus 类型（知识库类型创建后不变） */
    private final ConcurrentHashMap<Long, Boolean> routingCache = new ConcurrentHashMap<>();

    public EmbeddingServiceImpl(EmbeddingMapper embeddingMapper, MilvusUtil milvusUtil,
                                DocumentMapper documentMapper, ChunkService chunkService,
                                ObjectProvider<KnowledgeService> knowledgeServiceProvider,
                                RerankerUtil rerankerUtil, GraphRetrievalUtil graphRetrievalUtil) {
        this.embeddingMapper = embeddingMapper;
        this.milvusUtil = milvusUtil;
        this.documentMapper = documentMapper;
        this.chunkService = chunkService;
        this.knowledgeServiceProvider = knowledgeServiceProvider;
        this.rerankerUtil = rerankerUtil;
        this.graphRetrievalUtil = graphRetrievalUtil;
    }

    @Override
    public void saveVector(Long chunkId, String modelName, float[] vector) {
        long id = IdWorker.getId();
        String vectorStr = toVectorString(vector);
        // 反查 chunk 获取 knowledge_id（冗余列，供 HNSW pre-filter）
        Chunk chunk = chunkService.getById(chunkId);
        Long knowledgeId = chunk != null ? chunk.getKnowledgeId() : null;
        embeddingMapper.insertVector(id, chunkId, knowledgeId, modelName, vector.length, vectorStr);
    }

    /**
     * 批量存储向量（减少数据库往返次数）
     * <p>默认走 pgvector，调用方无 knowledgeId 时使用此方法</p>
     */
    @Override
    public void batchSaveVectors(List<Long> chunkIds, String modelName, List<float[]> vectors) {
        batchSaveVectors(null, chunkIds, modelName, vectors);
    }

    /**
     * 批量存储向量（带 knowledgeId，支持 Milvus 路由）
     *
     * @param knowledgeId 知识库ID（为 null 时走 pgvector）
     * @param chunkIds    分块ID列表
     * @param modelName   模型名称
     * @param vectors     向量数据列表，与 chunkIds 一一对应
     */
    public void batchSaveVectors(Long knowledgeId, List<Long> chunkIds, String modelName, List<float[]> vectors) {
        // Milvus 路由
        if (shouldRouteToMilvus(knowledgeId)) {
            // 确保 Collection 存在（首次写入时自动创建，使用缓存避免重复 RPC）
            if (!hasCollectionCached(knowledgeId) && !vectors.isEmpty()) {
                int dimension = vectors.get(0).length;
                milvusUtil.createCollectionForKnowledge(knowledgeId, dimension);
                collectionExistsCache.put(knowledgeId, true);
            }
            List<Chunk> chunks = chunkService.list(new LambdaQueryWrapper<Chunk>()
                    .in(Chunk::getId, chunkIds));
            Map<Long, Chunk> chunkMap = chunks.stream()
                    .collect(Collectors.toMap(Chunk::getId, c -> c));
            List<Long> documentIds = new ArrayList<>(chunkIds.size());
            List<String> contents = new ArrayList<>(chunkIds.size());
            for (Long chunkId : chunkIds) {
                Chunk chunk = chunkMap.get(chunkId);
                documentIds.add(chunk != null ? chunk.getDocumentId() : 0L);
                contents.add(chunk != null ? chunk.getContent() : "");
            }
            milvusUtil.insertVectors(knowledgeId, chunkIds, documentIds, contents, vectors);
            log.info("[Embedding] Milvus 批量写入: knowledgeId={}, count={}", knowledgeId, chunkIds.size());
            return;
        }

        // pgvector（原有逻辑）
        List<Map<String, Object>> batch = new ArrayList<>(chunkIds.size());
        // 批量写入时已知 knowledgeId（调用方传入）；为 null 时反查 chunk 补齐
        Long resolvedKnowledgeId = knowledgeId;
        if (resolvedKnowledgeId == null && !chunkIds.isEmpty()) {
            Chunk first = chunkService.getById(chunkIds.get(0));
            resolvedKnowledgeId = first != null ? first.getKnowledgeId() : null;
        }
        for (int i = 0; i < chunkIds.size(); i++) {
            float[] vector = vectors.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", IdWorker.getId());
            row.put("chunkId", chunkIds.get(i));
            row.put("knowledgeId", resolvedKnowledgeId);
            row.put("modelName", modelName);
            row.put("dimension", vector.length);
            row.put("vector", toVectorString(vector));
            batch.add(row);
        }
        embeddingMapper.batchInsertVectors(batch);
    }

    @Override
    public List<Map<String, Object>> searchSimilar(Long knowledgeId, float[] queryVector, int topK, double threshold) {
        String vectorStr = toVectorString(queryVector);
        List<Map<String, Object>> results = embeddingMapper.searchSimilar(vectorStr, knowledgeId, topK);

        // 过滤低于阈值的结果
        return results.stream()
                .filter(row -> {
                    Object score = row.get("score");
                    return score != null && ((Number) score).doubleValue() >= threshold;
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchSimilarSql(Long knowledgeId, float[] queryVector, int topK, double threshold) {
        return searchSimilarSql(knowledgeId, queryVector, topK, threshold, null);
    }

    /**
     * 向量检索（支持 Milvus 路由 + search_mode 参数 + 图检索融合）
     *
     * @param knowledgeId 知识库ID
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @param threshold   相似度阈值
     * @param queryParams 检索配置参数（search_mode/vector_weight/bm25_weight/bm25_top_k/use_graph_retrieval 等）
     * @return 检索结果（chunk_id, content, document_id, document_name, score）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchSimilarSql(Long knowledgeId, float[] queryVector,
                                                       int topK, double threshold,
                                                       Map<String, Object> queryParams) {
        // 0. 元数据过滤（Phase 3）：基于 Document.metadata JSONB 过滤检索范围
        List<Long> filteredDocIds = resolveMetadataFilterDocIds(knowledgeId, queryParams);
        if (filteredDocIds != null && filteredDocIds.isEmpty()) {
            // 有过滤条件但无匹配文档 → 直接短路返回空结果
            log.info("[Embedding] 元数据过滤后无匹配文档, knowledgeId={}", knowledgeId);
            return List.of();
        }

        // 1. 常规检索（Milvus 或 pgvector）
        List<Map<String, Object>> results;
        if (shouldRouteToMilvus(knowledgeId)) {
            results = searchMilvus(knowledgeId, queryVector, topK, threshold, queryParams);
        } else {
            results = searchPgvector(knowledgeId, queryVector, topK, threshold, queryParams);
        }

        // 2. 图检索（可选）：与常规结果 RRF 融合
        results = applyGraphRetrieval(knowledgeId, queryVector, results, topK, queryParams);

        // 3. Reranker（可选）
        results = applyReranker(results, queryParams, topK);

        // 4. 父子分块回填：命中子块若携带 parent_content（父块全文缓存），用父块全文替换 content，
        //    保证返回给大模型的上下文完整（父块不单独入库，仅缓存全文到子块）
        List<Map<String, Object>> limited = limitResults(results, topK);
        enrichWithParentContent(limited);
        return limited;
    }

    /**
     * 父子分块回填：批量按 chunk_id 取出 parent_content，命中子块时用父块全文替换 content。
     * 统一覆盖 pgvector 与 Milvus 两条检索路径（都在 searchSimilarSql 末尾执行）。
     */
    private void enrichWithParentContent(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (Map<String, Object> row : results) {
            long id = getChunkId(row);
            if (id > 0) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<Long, Chunk> chunkMap = chunkService.listByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Chunk::getId, c -> c));
        for (Map<String, Object> row : results) {
            Chunk c = chunkMap.get(getChunkId(row));
            if (c != null && c.getParentContent() != null && !c.getParentContent().isBlank()) {
                row.put("content", c.getParentContent());
            }
        }
    }

    /**
     * pgvector 检索路由：根据 search_mode 分发
     */
    private List<Map<String, Object>> searchPgvector(Long knowledgeId, float[] queryVector,
                                                      int topK, double threshold,
                                                      Map<String, Object> queryParams) {
        // 设置 ef_search 提升召回率（SET LOCAL 仅对当前事务生效），透传 query_params.hnsw_ef_search
        int hnswEf = getIntParam(queryParams, "hnsw_ef_search", DEFAULT_HNSW_EF_SEARCH);
        embeddingMapper.setHnswEfSearch(Math.max(hnswEf, topK));
        String metadataFilter = queryParams != null ? (String) queryParams.get(METADATA_FILTER_JSON_KEY) : null;
        String searchMode = queryParams != null && queryParams.get("search_mode") instanceof String s
                ? s : SEARCH_MODE_HYBRID;

        return switch (searchMode) {
            case SEARCH_MODE_KEYWORD -> {
                String queryText = getQueryParam(queryParams, "query_text", "");
                yield embeddingMapper.searchByFullText(queryText, knowledgeId, topK, metadataFilter);
            }
            case SEARCH_MODE_HYBRID -> searchPgHybrid(knowledgeId, queryVector, topK, threshold, queryParams, metadataFilter);
            default -> {
                String vectorStr = toVectorString(queryVector);
                yield embeddingMapper.searchSimilarWithThreshold(vectorStr, knowledgeId, topK, threshold, metadataFilter);
            }
        };
    }

    @Override
    public List<Map<String, Object>> searchSimilarRaw(Long knowledgeId, float[] queryVector, int topK) {
        String vectorStr = toVectorString(queryVector);
        return embeddingMapper.searchSimilar(vectorStr, knowledgeId, topK);
    }

    @Override
    public void deleteByKnowledgeId(Long knowledgeId) {
        embeddingMapper.deleteByKnowledgeId(knowledgeId);
        if (shouldRouteToMilvus(knowledgeId)) {
            milvusUtil.dropCollection(knowledgeId);
        }
        // 清理本地缓存，防止已删除知识库的残留条目导致错误路由
        collectionExistsCache.remove(knowledgeId);
        routingCache.remove(knowledgeId);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        embeddingMapper.deleteByDocumentId(documentId);
        // Milvus 侧：查询文档获取 knowledgeId，判断是否需要清理
        Document doc = documentMapper.selectById(documentId);
        if (doc != null && shouldRouteToMilvus(doc.getKnowledgeId())) {
            milvusUtil.deleteByDocumentId(doc.getKnowledgeId(), documentId);
        }
    }

    /**
     * 删除指定文档的向量（调用方已知 knowledgeId 和 type，避免重复查询）
     *
     * @param documentId  文档ID
     * @param knowledgeId 知识库ID
     * @param type        知识库类型
     */
    public void deleteByDocumentId(Long documentId, Long knowledgeId, KnowledgeType type) {
        embeddingMapper.deleteByDocumentId(documentId);
        if (type == KnowledgeType.MILVUS && milvusUtil.isAvailable()) {
            milvusUtil.deleteByDocumentId(knowledgeId, documentId);
        }
    }

    /**
     * pgvector 混合检索：向量检索 + 全文检索 → RRF 融合
     */
    private List<Map<String, Object>> searchPgHybrid(Long knowledgeId, float[] queryVector,
                                                      int topK, double threshold,
                                                      Map<String, Object> params, String metadataFilter) {
        String queryText = getQueryParam(params, "query_text", "");
        int recallTopK = Math.max(topK * 3, getIntParam(params, "recall_top_k", 30));
        float vectorWeight = getFloatParam(params, "vector_weight", 0.7f);
        float keywordWeight = getFloatParam(params, "keyword_weight", 0.3f);

        String vectorStr = toVectorString(queryVector);
        List<Map<String, Object>> vectorResults = embeddingMapper.searchSimilarWithThreshold(
                vectorStr, knowledgeId, recallTopK, 0, metadataFilter);
        List<Map<String, Object>> keywordResults = embeddingMapper.searchByFullText(
                queryText, knowledgeId, recallTopK, metadataFilter);

        return rrfFusion(vectorResults, keywordResults, vectorWeight, keywordWeight, topK, threshold,
                getIntParam(params, "rrf_k", DEFAULT_RRF_K));
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合排序
     *
     * @param vectorResults  向量检索结果
     * @param keywordResults 全文检索结果
     * @param vectorWeight   向量结果权重
     * @param keywordWeight  关键词结果权重
     * @param topK           返回数量
     * @param threshold      RRF 分数阈值（低于此值不返回，0 表示不过滤）
     * @param k              RRF 平滑常量（建议 60，越小结果越偏向高排名项）
     * @return 融合后的结果列表
     */
    private List<Map<String, Object>> rrfFusion(List<Map<String, Object>> vectorResults,
                                                 List<Map<String, Object>> keywordResults,
                                                 float vectorWeight, float keywordWeight,
                                                 int topK, double threshold, int k) {
        // chunkId → [rrfScore, bestOriginalScore, sourceRow]
        Map<Long, double[]> scoreMap = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> sourceMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            Map<String, Object> row = vectorResults.get(i);
            long chunkId = getChunkId(row);
            if (chunkId <= 0) continue;
            double rrfScore = vectorWeight / (k + i + 1);
            double originalScore = getScore(row);
            scoreMap.merge(chunkId, new double[]{rrfScore, originalScore},
                    (a, b) -> new double[]{a[0] + b[0], Math.max(a[1], b[1])});
            sourceMap.putIfAbsent(chunkId, row);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            Map<String, Object> row = keywordResults.get(i);
            long chunkId = getChunkId(row);
            if (chunkId <= 0) continue;
            double rrfScore = keywordWeight / (k + i + 1);
            double originalScore = getScore(row);
            scoreMap.merge(chunkId, new double[]{rrfScore, originalScore},
                    (a, b) -> new double[]{a[0] + b[0], Math.max(a[1], b[1])});
            sourceMap.putIfAbsent(chunkId, row);
        }

        return scoreMap.entrySet().stream()
                .filter(e -> threshold <= 0 || e.getValue()[0] >= threshold)
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(topK)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(sourceMap.get(e.getKey()));
                    row.put("score", e.getValue()[0]);
                    return row;
                })
                .toList();
    }

    /**
     * 解析元数据过滤条件（Phase 3）：将 queryParams.metadataFilter 转为 document.metadata JSONB 过滤，
     * 返回匹配的文档ID列表（null 表示无过滤条件）。
     */
    private List<Long> resolveMetadataFilterDocIds(Long knowledgeId, Map<String, Object> queryParams) {
        if (queryParams == null) {
            return null;
        }
        Object mf = queryParams.get(METADATA_FILTER_KEY);
        if (mf == null) {
            return null;
        }
        String json = toMetadataFilterJson(mf);
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return null;
        }
        try {
            LambdaQueryWrapper<Document> w = new LambdaQueryWrapper<>();
            w.eq(Document::getKnowledgeId, knowledgeId)
             .eq(Document::getDeleted, 0)
             .apply("metadata @> {0}::jsonb", json);
            List<Long> ids = documentMapper.selectList(w).stream().map(Document::getId).toList();
            queryParams.put(METADATA_FILTER_JSON_KEY, json);
            queryParams.put(METADATA_DOC_IDS_KEY, ids);
            return ids;
        } catch (Exception e) {
            log.warn("[Embedding] 元数据过滤解析失败, knowledgeId={}, error={}", knowledgeId, e.getMessage());
            return null;
        }
    }

    private String toMetadataFilterJson(Object mf) {
        try {
            if (mf instanceof String st) {
                String t = st.trim();
                return t.startsWith("{") ? t : null;
            }
            if (mf instanceof Map) {
                return new ObjectMapper().writeValueAsString(mf);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private long getChunkId(Map<String, Object> row) {
        Object id = row.get("chunk_id");
        if (id instanceof Number n) return n.longValue();
        return 0;
    }

    private double getScore(Map<String, Object> row) {
        Object score = row.get("score");
        return score instanceof Number n ? n.doubleValue() : 0;
    }

    private String getQueryParam(Map<String, Object> params, String key, String defaultValue) {
        if (params != null && params.get(key) instanceof String s) {
            return s;
        }
        return defaultValue;
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        if (params != null && params.get(key) instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private float getFloatParam(Map<String, Object> params, String key, float defaultValue) {
        if (params != null && params.get(key) instanceof Number n) {
            return n.floatValue();
        }
        return defaultValue;
    }

    private String toVectorString(float[] vector) {
        return VectorUtil.toVectorString(vector);
    }

    /**
     * 判断是否应路由到 Milvus（知识库类型缓存，Milvus 可用性实时检查）
     */
    private boolean shouldRouteToMilvus(Long knowledgeId) {
        if (knowledgeId == null || !milvusUtil.isAvailable()) {
            return false;
        }
        return routingCache.computeIfAbsent(knowledgeId, id -> {
            Knowledge knowledge = knowledgeServiceProvider.getObject().getById(id);
            return knowledge != null && knowledge.getType() == KnowledgeType.MILVUS;
        });
    }

    /**
     * 带缓存的 Collection 存在性检查（避免每次检索前 RPC 调用）
     */
    private boolean hasCollectionCached(Long knowledgeId) {
        return collectionExistsCache.computeIfAbsent(knowledgeId, milvusUtil::hasCollection);
    }

    /**
     * Milvus 检索路由：根据 search_mode 分发到 vector/keyword/hybrid 检索
     */
    private List<Map<String, Object>> searchMilvus(Long knowledgeId, float[] queryVector,
                                                    int topK, double threshold,
                                                    Map<String, Object> queryParams) {
        // Collection 不存在时直接返回空结果（使用缓存避免每次 RPC）
        if (!hasCollectionCached(knowledgeId)) {
            log.info("[Embedding] Milvus Collection 不存在, knowledgeId={}", knowledgeId);
            return List.of();
        }

        String searchMode = SEARCH_MODE_HYBRID;
        if (queryParams != null && queryParams.get("search_mode") instanceof String s) {
            searchMode = s;
        }

        List<Map<String, Object>> results;
        List<Long> docIds = queryParams != null ? (List<Long>) queryParams.get(METADATA_DOC_IDS_KEY) : null;
        switch (searchMode) {
            case SEARCH_MODE_KEYWORD -> {
                String queryText = queryParams != null && queryParams.get("query_text") instanceof String s
                        ? s : "";
                int bm25TopK = queryParams != null && queryParams.get("bm25_top_k") instanceof Number n
                        ? n.intValue() : topK * 3;
                bm25TopK = Math.max(topK, bm25TopK);
                float dropRatioSearch = getFloatParam(queryParams, "bm25_drop_ratio_search", 0.0f);
                results = milvusUtil.searchKeyword(knowledgeId, queryText, bm25TopK, dropRatioSearch, docIds);
            }
            case SEARCH_MODE_HYBRID -> {
                String queryText = queryParams != null && queryParams.get("query_text") instanceof String s
                        ? s : "";
                float vectorWeight = queryParams != null && queryParams.get("vector_weight") instanceof Number n
                        ? n.floatValue() : 0.7f;
                float bm25Weight = queryParams != null && queryParams.get("bm25_weight") instanceof Number n
                        ? n.floatValue() : 0.3f;
                int bm25TopK = queryParams != null && queryParams.get("bm25_top_k") instanceof Number n
                        ? n.intValue() : topK * 3;
                bm25TopK = Math.max(topK, bm25TopK);
                float dropRatioSearch = getFloatParam(queryParams, "bm25_drop_ratio_search", 0.0f);
                results = milvusUtil.searchHybrid(knowledgeId, queryText, queryVector,
                        topK, vectorWeight, bm25Weight, bm25TopK, dropRatioSearch, docIds);
            }
            default -> {
                // 透传 query_params.milvus_search_ef 控制召回精度（HNSW ef 参数）
                int milvusEf = getIntParam(queryParams, "milvus_search_ef", DEFAULT_MILVUS_SEARCH_EF);
                results = milvusUtil.searchVector(knowledgeId, queryVector, topK, threshold,
                        Math.max(milvusEf, topK), docIds);
            }
        }

        // 补充 document_name 字段（Milvus 只返回 document_id）
        enrichWithDocumentNames(results);

        return results;
    }

    /**
     * 统一限制最终返回数量，避免候选召回数被误当作最终 TopK。
     */
    private List<Map<String, Object>> limitResults(List<Map<String, Object>> results, int topK) {
        if (results == null || results.size() <= topK) {
            return results;
        }
        return results.subList(0, topK);
    }

    /**
     * 为 Milvus 检索结果补充 document_name 字段
     */
    private void enrichWithDocumentNames(List<Map<String, Object>> results) {
        List<Long> docIds = results.stream()
                .map(r -> r.get("document_id"))
                .filter(id -> id instanceof Long)
                .map(id -> (Long) id)
                .distinct()
                .toList();
        if (docIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(Document::getId, Document::getName));
        for (Map<String, Object> row : results) {
            Object docId = row.get("document_id");
            if (docId instanceof Long id) {
                row.put("document_name", nameMap.getOrDefault(id, "未知文档"));
            }
        }
    }

    /**
     * 应用 Reranker（如果启用）
     *
     * @param results     检索结果
     * @param queryParams 查询参数
     * @param topK        最终返回数量
     * @return 重排序后的结果
     */
    private List<Map<String, Object>> applyReranker(List<Map<String, Object>> results,
                                                      Map<String, Object> queryParams,
                                                      int topK) {
        boolean useReranker = queryParams != null
                && Boolean.TRUE.equals(queryParams.get("use_reranker"));
        if (!useReranker || results.isEmpty()) {
            return results;
        }
        String queryText = getQueryParam(queryParams, "query_text", "");
        if (queryText.isBlank()) {
            return results;
        }
        String rerankerModel = getQueryParam(queryParams, "reranker_model", "");
        return rerankerUtil.rerank(queryText, results, topK, rerankerModel);
    }

    /**
     * 应用图检索（如果启用）：图检索结果与常规结果 RRF 融合
     *
     * @param knowledgeId 知识库ID
     * @param queryVector 查询向量
     * @param results     常规检索结果
     * @param topK        最终返回数量
     * @param queryParams 查询参数
     * @return 融合后的结果
     */
    private List<Map<String, Object>> applyGraphRetrieval(Long knowledgeId, float[] queryVector,
                                                           List<Map<String, Object>> results,
                                                           int topK, Map<String, Object> queryParams) {
        boolean useGraph = queryParams != null
                && Boolean.TRUE.equals(queryParams.get("use_graph_retrieval"));
        if (!useGraph || !shouldRouteToMilvus(knowledgeId)) {
            return results;
        }

        int graphEntityTopK = getIntParam(queryParams, "graph_entity_top_k", 10);
        int graphTripleTopK = getIntParam(queryParams, "graph_triple_top_k", 10);
        int graphMaxNodes = getIntParam(queryParams, "graph_max_nodes", 100);
        int graphTopK = getIntParam(queryParams, "graph_top_k", 5);
        double graphWeight = getFloatParam(queryParams, "graph_weight", 0.3f);
        double pprDamping = getFloatParam(queryParams, "ppr_damping", 0.85f);
        int pprIterations = getIntParam(queryParams, "ppr_iterations", DEFAULT_PPR_ITERATIONS);
        int rrfK = getIntParam(queryParams, "rrf_k", DEFAULT_RRF_K);

        try {
            String queryText = getQueryParam(queryParams, "query_text", "");
            List<Map<String, Object>> graphResults = graphRetrievalUtil.search(
                    knowledgeId, queryVector, queryText, graphEntityTopK, graphTripleTopK,
                    graphMaxNodes, graphTopK, pprDamping, pprIterations);

            if (graphResults.isEmpty()) {
                return results;
            }

            // 为图检索结果分配唯一负数 chunk_id，避免与常规结果冲突
            for (int i = 0; i < graphResults.size(); i++) {
                graphResults.get(i).put("chunk_id", -(i + 1L));
            }

            // RRF 融合：常规结果权重=1.0，图检索结果权重=graphWeight
            return rrfFusion(results, graphResults, 1.0f, (float) graphWeight, topK, 0, rrfK);
        } catch (Exception e) {
            log.error("[Embedding] 图检索异常，返回常规结果: {}", e.getMessage(), e);
            return results;
        }
    }
}
