package com.lengbot.agent.tool.knowledge;

import com.lengbot.constant.RagResultType;
import com.lengbot.vo.QaPairSearchResultVO;
import com.lengbot.entity.Knowledge;
import com.lengbot.service.AgentService;
import com.lengbot.service.MessageService;
import com.lengbot.service.TextEmbeddingService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.service.KnowledgeRetrievalService;
import com.lengbot.service.QaPairService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.util.RagParamResolver;
import com.lengbot.util.TextNormalizeUtil;
import com.lengbot.util.ModelCalls;
import com.lengbot.tool.ToolEventEmitter;
import reactor.core.publisher.Sinks;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 内置工具 — 知识库检索
 * <p>由 {@link com.lengbot.tool.registrar.ToolRegistrar} 统一注册，type=knowledge，
 * 当 Agent 绑定知识库时由中间件自动注入。</p>
 *
 * @author lw
 * @since 2026-05-22
 */
@Slf4j
@Component("queryKnowledgeTool")
@SystemTool(displayName = "知识库检索", description = "搜索智能体绑定的知识库，获取与问题相关的文档内容", type = "knowledge", tags = {"知识库"},
        outputExample = "{\"total\":2,\"qa_answer\":null,\"results\":[{\"result_type\":\"chunk\",\"content\":\"文档内容片段...\",\"score\":0.85,\"document_id\":1234567890,\"document_name\":\"产品说明书\",\"knowledge_id\":9876543210},{\"result_type\":\"qa_pair\",\"content\":\"问答对回答\",\"score\":0.72,\"question\":\"如何配置系统？\",\"answer\":\"请参考配置指南\",\"knowledge_id\":9876543210}]}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"integer\",\"description\":\"匹配结果总数\"},\"qa_answer\":{\"type\":\"string\",\"description\":\"QA优先命中时直接返回的答案（无命中时为null）\"},\"results\":{\"type\":\"array\",\"description\":\"检索结果列表\",\"items\":{\"type\":\"object\",\"properties\":{\"result_type\":{\"type\":\"string\",\"description\":\"结果类型：chunk=文档片段，qa_pair=问答对\"},\"content\":{\"type\":\"string\",\"description\":\"匹配内容文本\"},\"score\":{\"type\":\"number\",\"description\":\"相似度得分（0-1）\"},\"document_id\":{\"type\":\"integer\",\"description\":\"文档ID（仅chunk类型）\"},\"document_name\":{\"type\":\"string\",\"description\":\"文档名（仅chunk类型）\"},\"question\":{\"type\":\"string\",\"description\":\"问题（仅qa_pair类型）\"},\"answer\":{\"type\":\"string\",\"description\":\"标准答案（仅qa_pair类型）\"},\"knowledge_id\":{\"type\":\"integer\",\"description\":\"所属知识库ID\"}}}}}}")
@RequiredArgsConstructor
public class QueryKnowledgeTool {

    private final AgentService agentService;
    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;
    /** 文本向量生成服务（AgentScope 引擎） */
    private final TextEmbeddingService textEmbeddingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final RagParamResolver ragParamResolver;
    private final ObjectMapper objectMapper;
    private final com.lengbot.service.QueryRewriteService queryRewriteService;
    private final MessageService messageService;

    @Autowired
    @Qualifier("lengBotExecutor")
    private Executor lengBotExecutor;

    /**
     * 按请求ID存储的搜索结果（跨线程安全，Caffeine 自动过期 + 容量限制）
     * <p>工具在 lengBotExecutor 线程池执行，无法用 ThreadLocal 传递结果给主线程，
     * 改用 Caffeine Cache 以 requestId 为 key 存储</p>
     */
    private static final Cache<String, List<Map<String, Object>>> SEARCH_RESULTS_CACHE =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(5, TimeUnit.MINUTES).build();

    /** 查询改写参考的对话轮数（一轮=一问一答） */
    private static final int REWRITE_HISTORY_TURNS = 3;
    /** 单条历史消息参与改写时的字符上限 */
    private static final int MAX_HISTORY_ONE_MSG_CHARS = 300;
    /** RRF 平滑常数，抑制头部名次权重过大 */
    private static final int RRF_K = 60;
    /** 双路召回融合后默认保留条数（topK 异常时兜底） */
    private static final int DEFAULT_DUAL_RECALL_TOP_K = 10;

    @Tool(name = "query_knowledge",
          description = "搜索当前对话智能体绑定的知识库，获取与问题相关的文档内容。当用户问题涉及特定领域知识、需要查找文档资料时调用此工具。只需传入 question，不要传入 agentId。")
    public String queryKnowledge(
            @ToolParam(name = "question", description = "搜索问题")
            @ToolParamMeta(example = "如何配置模型参数", required = true) String question,
            ToolCallParam context) {
        String requestId = (String) context.getRuntimeContext().get("requestId");
        Long finalAgentId = resolveAgentId(context);
        // 捕获当前线程的实时 Sink：下方检索在 lengBotExecutor 子线程执行，须透传进去，
        // 否则 emit 落到 EVENTS ThreadLocal 既无法实时推送又会泄漏。
        Sinks.Many<String> statusSink = ToolEventEmitter.currentSink();
        log.info("[Tool:query_knowledge] 开始检索: agentId={}, question={}", finalAgentId, question);

        if (finalAgentId == null) {
            return "无法确定当前智能体，知识库检索已跳过。请从对话页选择 Agent 后重试。";
        }

        if (question == null || question.isBlank()) {
            return "搜索问题不能为空，请提供具体的搜索内容。";
        }

        // 1. 获取 Agent 绑定的知识库 ID 列表（@ 仅影响提示词优先级，不收窄检索范围）
        List<Long> knowledgeIds = agentService.getKnowledgeIds(finalAgentId);
        log.info("[Tool:query_knowledge] Agent绑定知识库: agentId={}, knowledgeIds={}", finalAgentId, knowledgeIds);
        if (knowledgeIds.isEmpty()) {
            return "该智能体未绑定任何知识库，无法检索。";
        }

        try {
            // 2. 查询改写（可选）：将模糊/短查询改写为更适合检索的形式
            // 带上对话历史，使「它多少钱」「那第二条呢」这类省略/指代可被消解；
            // 历史缺失时自动退化为无上下文改写。
            String effectiveQuery = question;
            if (isQueryRewriteEnabled(knowledgeIds)) {
                ToolEventEmitter.emit("正在改写查询...");
                Long rewriteSessionId = resolveSessionId(context);
                String rewritten = queryRewriteService.rewrite(
                        question, buildHistoryContext(rewriteSessionId));
                if (!rewritten.equals(question)) {
                    log.info("[Tool:query_knowledge] 查询已改写: {} → {}", question, rewritten);
                    ToolEventEmitter.emit("查询改写：" + rewritten);
                    effectiveQuery = rewritten;
                }
            }
            final String searchQuery = effectiveQuery;
            // 双路召回：改写生效时，同时保留原句与改写句两条检索式。
            // 改写可能偏移原意，原句是安全底线；两者融合可兼顾召回率与准确性。
            final boolean dualRecall = !searchQuery.equals(question)
                    && isDualRecallEnabled(knowledgeIds);
            final String originalQuery = dualRecall ? question : null;
            if (dualRecall) {
                ToolEventEmitter.emit("双路召回：原句 + 改写句并行检索");
            }

            // 3. 并行检索多个知识库（由统一入口路由本地向量库或 Dify Dataset）。
            List<CompletableFuture<List<Map<String, Object>>>> futures = knowledgeIds.stream()
                    .map(knowledgeId -> CompletableFuture.supplyAsync(() -> {
                        // 透传实时 Sink 到子线程：使下方 emit 能实时推给前端，并在 finally 清理避免 EVENTS 泄漏。
                        if (statusSink != null) {
                            ToolEventEmitter.setupSink(statusSink);
                        }
                        try {
                            Knowledge knowledge = knowledgeService.getById(knowledgeId);
                            if (knowledge == null) {
                                log.warn("[Tool:query_knowledge] 知识库不存在: knowledgeId={}", knowledgeId);
                                return List.<Map<String, Object>>of();
                            }
                            String kbName = knowledge.getName();
                            int topK = resolveTopK(knowledge);
                            double threshold = resolveThreshold(knowledge);
                            boolean qaEnabled = resolveQaEnabled(knowledge);
                            int qaTopK = qaEnabled ? resolveQaTopK(knowledge) : 0;
                            double qaThreshold = qaEnabled ? resolveQaThreshold(knowledge) : 0;
                            boolean qaPriority = qaEnabled && resolveQaPriority(knowledge);
                            log.info("[Tool:query_knowledge] 检索知识库: name={}, knowledgeId={}, topK={}, threshold={}, qaEnabled={}, qaTopK={}, qaThreshold={}, qaPriority={}",
                                    kbName, knowledgeId, topK, threshold, qaEnabled, qaTopK, qaThreshold, qaPriority);

                            // 并行检索 Chunk 和 QA Pair
                            ToolEventEmitter.emit("正在检索知识库「" + kbName + "」的文档块...");
                            CompletableFuture<List<Map<String, Object>>> chunkFuture = CompletableFuture.supplyAsync(() -> {
                                try {
                                    Map<String, Object> searchParams = buildSearchParams(knowledge, searchQuery);
                                    return knowledgeRetrievalService.retrieve(knowledge, searchQuery, topK, threshold, searchParams);
                                } catch (Exception e) {
                                    log.warn("[Tool:query_knowledge] Chunk检索失败: knowledgeId={}", knowledgeId);
                                    return List.<Map<String, Object>>of();
                                }
                            }, lengBotExecutor);

                            // 第二路：用原始问句再检索一次，与改写路结果融合后去重。
                            CompletableFuture<List<Map<String, Object>>> originalFuture;
                            if (dualRecall) {
                                originalFuture = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        Map<String, Object> params = buildSearchParams(knowledge, originalQuery);
                                        return knowledgeRetrievalService.retrieve(knowledge, originalQuery, topK, threshold, params);
                                    } catch (Exception e) {
                                        log.warn("[Tool:query_knowledge] 原句召回失败: knowledgeId={}", knowledgeId);
                                        return List.<Map<String, Object>>of();
                                    }
                                }, lengBotExecutor);
                            } else {
                                originalFuture = CompletableFuture.completedFuture(List.of());
                            }

                            CompletableFuture<List<Map<String, Object>>> qaFuture;
                            if (qaEnabled && knowledge.getType() != com.lengbot.enums.KnowledgeType.DIFY) {
                                ToolEventEmitter.emit("正在检索知识库「" + kbName + "」的问答对...");
                                qaFuture = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        List<QaPairSearchResultVO> qaResults = qaPairService.searchSimilar(
                                                knowledgeId, ModelCalls.toFloatArray(embedText(searchQuery)), qaTopK, qaThreshold);
                                        return qaResults.stream().map(qa -> {
                                            Map<String, Object> row = new java.util.HashMap<>();
                                            row.put("id", qa.getId());
                                            row.put("question", qa.getQuestion());
                                            row.put("content", qa.getAnswer());
                                            row.put("answer", qa.getAnswer());
                                            row.put("score", qa.getScore());
                                            row.put("knowledge_id", knowledgeId.toString());
                                            row.put("document_name", "问答对");
                                            row.put("result_type", RagResultType.QA_PAIR);
                                            return row;
                                        }).toList();
                                    } catch (Exception e) {
                                        log.warn("[Tool:query_knowledge] QA Pair检索失败: knowledgeId={}", knowledgeId);
                                        return List.<Map<String, Object>>of();
                                    }
                                }, lengBotExecutor);
                            } else {
                                qaFuture = CompletableFuture.completedFuture(List.of());
                            }

                            // 合并结果
                            List<Map<String, Object>> chunkResults = chunkFuture.join();
                            if (dualRecall) {
                                List<Map<String, Object>> originalResults = originalFuture.join();
                                int rewrittenCount = chunkResults.size();
                                chunkResults = mergeRecallPaths(chunkResults, originalResults, topK);
                                log.info("[Tool:query_knowledge] 双路RRF融合: name={}, rewritten={}, original={}, fused={}",
                                        kbName, rewrittenCount, originalResults.size(), chunkResults.size());
                            }
                            List<Map<String, Object>> qaResults = qaFuture.join();

                            // 标记 chunk 结果类型
                            chunkResults.forEach(row -> row.putIfAbsent("result_type", RagResultType.CHUNK));

                            log.info("[Tool:query_knowledge] 知识库检索结果: name={}, chunkCount={}, qaCount={}, dualRecall={}",
                                    kbName, chunkResults.size(), qaResults.size(), dualRecall);
                            ToolEventEmitter.emit("知识库「" + kbName + "」: 文档块 " + chunkResults.size() + " 条" + (qaEnabled ? ", 问答对 " + qaResults.size() + " 条" : ""));

                            // QA 优先返回：高分 QA 标记特殊字段，外层统一处理
                            List<Map<String, Object>> allKbResults = new ArrayList<>();
                            if (qaPriority && !qaResults.isEmpty()) {
                                double topQaScore = ((Number) qaResults.get(0).get("score")).doubleValue();
                                if (topQaScore >= qaThreshold) {
                                    Map<String, Object> qaPriorityResult = new java.util.HashMap<>(qaResults.get(0));
                                    qaPriorityResult.put("_qa_priority", true);
                                    allKbResults.add(qaPriorityResult);
                                    log.info("[Tool:query_knowledge] QA优先命中: knowledgeId={}, score={}", knowledgeId, topQaScore);
                                    return allKbResults;
                                }
                            }
                            allKbResults.addAll(qaResults);
                            allKbResults.addAll(chunkResults);
                            return allKbResults;
                        } catch (Exception e) {
                            log.warn("[Tool:query_knowledge] 知识库检索失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
                            return List.<Map<String, Object>>of();
                        } finally {
                            ToolEventEmitter.teardownSink();
                            ToolEventEmitter.clear();
                        }
                    }, lengBotExecutor))
                    .toList();

            // 5. 合并结果，检查是否有 QA 优先命中
            List<Map<String, Object>> allResults = new ArrayList<>();
            Map<String, Object> qaPriorityHit = null;
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                for (Map<String, Object> row : future.join()) {
                    if (Boolean.TRUE.equals(row.get("_qa_priority"))) {
                        qaPriorityHit = row;
                    } else {
                        allResults.add(row);
                    }
                }
            }

            // 5.1 QA 优先命中：返回 JSON（含 qa_answer）
            if (qaPriorityHit != null) {
                String qaAnswer = (String) qaPriorityHit.get("answer");
                String qaQuestion = (String) qaPriorityHit.get("question");
                double qaScore = ((Number) qaPriorityHit.get("score")).doubleValue();
                ToolEventEmitter.emit("命中高匹配问答对（相似度 " + String.format("%.2f", qaScore) + "），直接返回标准答案");
                if (requestId != null) {
                    SEARCH_RESULTS_CACHE.put(requestId, List.of(qaPriorityHit));
                }
                log.info("[Tool:query_knowledge] QA优先返回: question={}, score={}", qaQuestion, qaScore);
                Map<String, Object> output = new java.util.LinkedHashMap<>();
                output.put("total", 1);
                output.put("qa_answer", qaAnswer);
                output.put("results", List.of());
                return objectMapper.writeValueAsString(output);
            }

            // 5.2 按 requestId 存储原始结果，供 ChatService 读取并持久化到消息 metadata
            if (requestId != null) {
                SEARCH_RESULTS_CACHE.put(requestId, allResults);
            }

            ToolEventEmitter.emit("共找到 " + allResults.size() + " 条相关内容");

            if (allResults.isEmpty()) {
                log.warn("[Tool:query_knowledge] 未找到结果: agentId={}, knowledgeIds={}, question={}",
                        finalAgentId, knowledgeIds, question);
                Map<String, Object> empty = new java.util.LinkedHashMap<>();
                empty.put("total", 0);
                empty.put("qa_answer", null);
                empty.put("results", List.of());
                return objectMapper.writeValueAsString(empty);
            }

            // 6. 构建 JSON 返回
            Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("total", allResults.size());
            output.put("qa_answer", null);
            output.put("results", allResults.stream().map(row -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("result_type", row.get("result_type"));
                item.put("content", TextNormalizeUtil.normalizeForPrompt(String.valueOf(row.get("content"))));
                item.put("score", row.get("score"));
                if (RagResultType.CHUNK.equals(row.get("result_type"))) {
                    Object docIdRaw = row.get("document_id");
                    item.put("document_id", docIdRaw != null ? docIdRaw.toString() : null);
                    item.put("document_name", row.get("document_name"));
                }
                if (RagResultType.QA_PAIR.equals(row.get("result_type"))) {
                    item.put("question", row.get("question"));
                    item.put("answer", row.get("answer"));
                }
                Object kbIdRaw = row.get("knowledge_id");
                item.put("knowledge_id", kbIdRaw != null ? kbIdRaw.toString() : null);
                return item;
            }).toList());

            log.info("[Tool:query_knowledge] 检索完成: agentId={}, results={}", finalAgentId, allResults.size());
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            log.error("[Tool:query_knowledge] 检索异常: agentId={}, error={}", finalAgentId, e.getMessage(), e);
            return "知识库检索过程中发生错误：" + e.getMessage();
        }
    }

    private static Long resolveAgentId(ToolCallParam context) {
        if (context == null || context.getRuntimeContext() == null) {
            return null;
        }
        Object agentIdObj = context.getRuntimeContext().get("agentId");
        if (agentIdObj instanceof Number num) {
            long id = num.longValue();
            return id > 0 ? id : null;
        }
        if (agentIdObj instanceof String str && !str.isBlank()) {
            try {
                long id = Long.parseLong(str.trim());
                return id > 0 ? id : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double[] embedText(String text) {
        return textEmbeddingService.embed(text);
    }

    /**
     * 从工具上下文解析 sessionId。缺失时返回 null，改写退化为无上下文模式。
     */
    private static Long resolveSessionId(ToolCallParam context) {
        if (context == null || context.getRuntimeContext() == null) {
            return null;
        }
        Object sid = context.getRuntimeContext().get("sessionId");
        if (sid instanceof Number num) {
            long id = num.longValue();
            return id > 0 ? id : null;
        }
        if (sid instanceof String str && !str.isBlank() && !"default".equals(str)) {
            try {
                long id = Long.parseLong(str.trim());
                return id > 0 ? id : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 构建供改写使用的对话历史（最近若干轮用户/助手正文）。
     * <p>只取纯文本消息，跳过工具事件与空内容；失败时返回 null，不影响主流程。</p>
     */
    private String buildHistoryContext(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            List<com.lengbot.entity.Message> messages = messageService.listBySessionId(sessionId);
            if (messages == null || messages.isEmpty()) {
                return null;
            }
            // 仅保留最近若干条，倒序取再还原顺序
            int keep = Math.min(messages.size(), REWRITE_HISTORY_TURNS * 2);
            List<com.lengbot.entity.Message> recent = new ArrayList<>(
                    messages.subList(messages.size() - keep, messages.size()));

            StringBuilder sb = new StringBuilder();
            for (com.lengbot.entity.Message m : recent) {
                if (m.getRole() == null || m.getContent() == null) continue;
                String role = m.getRole().getCode();
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                String text = m.getContent().trim();
                if (text.isEmpty()) continue;
                if (text.length() > MAX_HISTORY_ONE_MSG_CHARS) {
                    text = text.substring(0, MAX_HISTORY_ONE_MSG_CHARS) + "…";
                }
                sb.append("user".equals(role) ? "用户：" : "助手：").append(text).append('\n');
            }
            String ctx = sb.toString().trim();
            return ctx.isEmpty() ? null : ctx;
        } catch (Exception e) {
            log.warn("[Tool:query_knowledge] 构建改写上下文失败，退化为无上下文改写: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 双路召回融合：对改写路与原句路结果按 RRF（倒数排名融合）打分后去重。
     * <p>RRF 只依赖名次、不依赖分数量纲，适合合并两次独立向量检索；
     * 同一 chunk 命中两路时得分叠加自然靠前。融合分单独写入 _rrf_score
     * 仅用于排序，score 保留原始向量相似度——下游的前端相似度展示与
     * metadata 持久化都按 0~1 量级解读 score，不能被 RRF 的 0.01 量级污染。</p>
     *
     * @param rewrittenPath 改写句召回结果（有序，越靠前越相关）
     * @param originalPath  原句召回结果（有序）
     * @param topK          最终保留条数
     * @return 融合去重后的结果
     */
    private List<Map<String, Object>> mergeRecallPaths(List<Map<String, Object>> rewrittenPath,
                                                       List<Map<String, Object>> originalPath,
                                                       int topK) {
        // 单路为空时直接回退另一路：保留原始 score 语义，不标记 _dual_recall
        if (rewrittenPath == null || rewrittenPath.isEmpty()) {
            return originalPath == null ? List.of() : new ArrayList<>(originalPath);
        }
        if (originalPath == null || originalPath.isEmpty()) {
            return new ArrayList<>(rewrittenPath);
        }

        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        Map<String, Double> fused = new LinkedHashMap<>();

        accumulateRrf(rewrittenPath, best, fused);
        accumulateRrf(originalPath, best, fused);

        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK > 0 ? topK : DEFAULT_DUAL_RECALL_TOP_K)
                .map(e -> {
                    Map<String, Object> row = new java.util.HashMap<>(best.get(e.getKey()));
                    row.put("_rrf_score", e.getValue());
                    row.put("_dual_recall", true);
                    return row;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 累积单路 RRF 得分：score += 1 / (k + rank)，k 平滑常数取 60。 */
    private void accumulateRrf(List<Map<String, Object>> path,
                               Map<String, Map<String, Object>> best,
                               Map<String, Double> fused) {
        int rank = 0;
        for (Map<String, Object> row : path) {
            if (row == null) continue;
            String key = recallKey(row);
            if (key == null) continue;
            rank++;
            fused.merge(key, 1.0 / (RRF_K + rank), Double::sum);
            // 保留该 key 下向量分更高的一条作为内容来源
            Map<String, Object> prev = best.get(key);
            if (prev == null || scoreOf(row) > scoreOf(prev)) {
                best.put(key, row);
            }
        }
    }

    /** 结果去重键：chunk 用 chunk_id，QA 用 id+question，均无则退回内容指纹。 */
    private String recallKey(Map<String, Object> row) {
        Object chunkId = row.get("chunk_id");
        if (chunkId != null) {
            return "c:" + chunkId;
        }
        Object id = row.get("id");
        if (id != null) {
            return "q:" + id + ":" + row.get("question");
        }
        Object content = row.get("content");
        return content != null ? "t:" + content.hashCode() : null;
    }

    private double scoreOf(Map<String, Object> row) {
        Object s = row.get("score");
        return s instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * 双路召回开关：任一绑定知识库显式开启即启用。
     * <p>默认关闭——未配置时行为与历史一致，避免改变既有检索效果。</p>
     */
    private boolean isDualRecallEnabled(List<Long> knowledgeIds) {
        for (Long knowledgeId : knowledgeIds) {
            Knowledge knowledge = knowledgeService.getById(knowledgeId);
            if (knowledge == null) continue;
            Map<String, Object> qp = parseQueryParams(knowledge);
            if (Boolean.TRUE.equals(qp.get("dual_recall"))) return true;
        }
        return false;
    }

    private int resolveTopK(Knowledge knowledge) {
        return ragParamResolver.resolveTopK(null, parseQueryParams(knowledge),
                knowledge.getConfig(), RagParamResolver.DEFAULT_TOP_K);
    }

    private double resolveThreshold(Knowledge knowledge) {
        return ragParamResolver.resolveThreshold(null, parseQueryParams(knowledge),
                knowledge.getConfig(), RagParamResolver.DEFAULT_THRESHOLD);
    }

    private boolean resolveQaEnabled(Knowledge knowledge) {
        Map<String, Object> qp = parseQueryParams(knowledge);
        if (qp.get("qa_enabled") instanceof Boolean b) return b;
        return true;
    }

    private int resolveQaTopK(Knowledge knowledge) {
        Map<String, Object> qp = parseQueryParams(knowledge);
        if (qp.get("qa_top_k") instanceof Number n) return n.intValue();
        return ragParamResolver.resolveTopK(null, null, knowledge.getConfig(), 3);
    }

    private double resolveQaThreshold(Knowledge knowledge) {
        Map<String, Object> qp = parseQueryParams(knowledge);
        if (qp.get("qa_threshold") instanceof Number n) return n.doubleValue();
        return ragParamResolver.resolveThreshold(null, null, knowledge.getConfig(), 0.85);
    }

    private boolean resolveQaPriority(Knowledge knowledge) {
        Map<String, Object> qp = parseQueryParams(knowledge);
        if (qp.get("qa_priority") instanceof Boolean b) return b;
        if (knowledge.getConfig() != null && !knowledge.getConfig().isBlank()) {
            try {
                var node = objectMapper.readTree(knowledge.getConfig());
                if (node.has("qaPriority")) return node.get("qaPriority").asBoolean(true);
            } catch (Exception ignored) {}
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseQueryParams(Knowledge knowledge) {
        if (knowledge.getQueryParams() == null || knowledge.getQueryParams().isBlank()
                || "{}".equals(knowledge.getQueryParams())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(knowledge.getQueryParams(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 检查是否启用查询改写：任一绑定知识库开启即启用
     */
    private boolean isQueryRewriteEnabled(List<Long> knowledgeIds) {
        for (Long knowledgeId : knowledgeIds) {
            Knowledge knowledge = knowledgeService.getById(knowledgeId);
            if (knowledge == null) continue;
            Map<String, Object> qp = parseQueryParams(knowledge);
            if (Boolean.TRUE.equals(qp.get("query_rewrite"))) return true;
        }
        return false;
    }

    /**
     * 按 requestId 获取工具执行期间的搜索结果（跨线程安全）
     *
     * @param requestId 请求ID
     * @return 搜索结果列表，不存在则返回空列表
     */
    public static List<Map<String, Object>> getSearchResults(String requestId) {
        if (requestId == null) return List.of();
        List<Map<String, Object>> data = SEARCH_RESULTS_CACHE.getIfPresent(requestId);
        if (data != null) {
            SEARCH_RESULTS_CACHE.invalidate(requestId);
        }
        return data != null ? data : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSearchParams(Knowledge knowledge, String question) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (knowledge.getQueryParams() != null && !knowledge.getQueryParams().isBlank()
                && !"{}".equals(knowledge.getQueryParams())) {
            try {
                params.putAll(objectMapper.readValue(knowledge.getQueryParams(), Map.class));
            } catch (Exception ignored) {
            }
        }
        params.put("query_text", question);
        return params;
    }
}
