package com.lengbot.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.task.TaskCancelledException;
import com.lengbot.entity.Task;
import com.lengbot.model.ModelFactory;
import com.lengbot.service.TaskService;
import com.lengbot.service.TextEmbeddingService;
import com.lengbot.util.MilvusUtil;
import com.lengbot.util.ModelErrorClassifier;
import com.lengbot.util.Msgs;
import com.lengbot.util.Neo4jUtil;
import com.lengbot.util.RedisUtil;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 社区摘要生成任务执行器（GraphRAG「社区总结」链路 · 第 2 步）
 *
 * <p><b>作用</b>：读取第 1 步产出的 Community 节点，把每个社区的成员实体与内部关系组织成
 * 结构化上下文，交给 LLM 提炼一段<b>高层语义摘要</b>，回写到 {@code Community.summary}。
 *
 * <p><b>为什么需要这一步</b>：局部图检索只能回答「某个实体跟什么有关」这类点对点问题。
 * 社区摘要把一簇实体归纳成一段可读文本，让「整个知识库在讲什么」「有哪些大主题」
 * 这类宏观问题第一次有了可检索的高层语料 —— 这正是微软 GraphRAG 中缺的那一半。
 *
 * <p><b>前置依赖</b>：必须先运行社区检测（{@link CommunityDetectExecutor}），
 * 图上要有带 {@code community_id} 的实体与对应的 Community 节点。
 *
 * <p><b>token 保护</b>：社区可能包含成千上万条关系，直接全量喂给 LLM 会爆上下文，
 * 因此每个社区的实体数与关系数均有上限，超出部分在文本中标注总数后截断。
 *
 * @author lw
 * @since 2026-10-10
 */
@Slf4j
@Component("communitySummaryExecutor")
@RequiredArgsConstructor
public class CommunitySummarizeExecutor implements TaskExecutor {

    /** 默认并发数（同时向 LLM 发起的社区数） */
    private static final int DEFAULT_CONCURRENCY = 4;

    /** 单个社区最多送给 LLM 的实体个数 */
    private static final int DEFAULT_MAX_ENTITIES = 120;

    /** 单个社区最多送给 LLM 的关系条数 */
    private static final int DEFAULT_MAX_TRIPLES = 250;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个知识图谱分析专家，擅长把一组紧密关联的实体及其关系，提炼成一段高层语义摘要。

            你会拿到一个「社区」：它是知识图谱中关系紧密的一簇实体。请判断这一簇实体整体上在讲什么。

            要求：
            1. 用简体中文输出，控制在 150-300 字之间
            2. 说明这个社区的核心主题、涉及的关键对象，以及这些对象之间主要发生了什么关系
            3. 不要逐条罗列关系清单，要做归纳提炼；目标读者是没看过原始图谱的人，要让他看懂这段在讲什么
            4. 只输出摘要正文：不要标题、不要 markdown 符号、不要"这个社区……"这类客套开头
            5. 若提供的信息过于零散、无法归纳出有意义的主题，只输出四个字：信息不足

            输出示例：
            围绕某公司的自动驾驶研发体系展开，核心对象是张伟团队、视觉感知算法与 L4 级自动驾驶平台。张伟团队主导算法
            迭代并推动平台落地，平台又依赖高精地图与激光雷达数据，整体呈现"算法—平台—数据"三层技术链路，关注点集中在
            感知精度提升与实车验证两个方向。
            """;

    private static final String SUMMARY_USER_PROMPT = """
            社区成员实体（共 %d 个%s）：
            %s

            社区内关系（共 %d 条%s）：
            %s

            请为上述社区生成摘要。
            """;

    /** LLM 判定信息不足时的固定输出 */
    private static final String INSUFFICIENT = "信息不足";

    private final Neo4jUtil neo4jUtil;
    private final MilvusUtil milvusUtil;
    private final ModelFactory modelFactory;
    private final TaskService taskService;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    /** 文本嵌入服务（AgentScope 引擎），未配置嵌入模型时可为 null */
    @Autowired(required = false)
    private TextEmbeddingService textEmbeddingService;

    @Override
    public String execute(Task task) throws Exception {
        if (!neo4jUtil.isAvailable()) {
            throw new RuntimeException("Neo4j 图数据库不可用，无法生成社区摘要");
        }

        JsonNode payload = objectMapper.readTree(task.getPayload());
        if (payload == null || !payload.has("knowledgeId")) {
            throw new RuntimeException("任务 payload 缺少 knowledgeId");
        }
        Long knowledgeId = payload.get("knowledgeId").asLong();
        Long providerId = resolvePayloadLong(payload, "providerId");
        // 注意：这些变量会被 lambda 捕获，必须保持 effectively final（不可二次赋值）
        final String modelId = payload.has("modelId") && !payload.get("modelId").asText("").isBlank()
                ? payload.get("modelId").asText("").trim() : null;
        final Map<String, Object> modelParams = parseModelParams(payload);
        int concurrency = payload.has("concurrency") ? payload.get("concurrency").asInt(DEFAULT_CONCURRENCY) : DEFAULT_CONCURRENCY;
        concurrency = Math.max(1, Math.min(concurrency, 32));
        int maxEntities = payload.has("maxEntities") ? payload.get("maxEntities").asInt(DEFAULT_MAX_ENTITIES) : DEFAULT_MAX_ENTITIES;
        int maxTriples = payload.has("maxTriples") ? payload.get("maxTriples").asInt(DEFAULT_MAX_TRIPLES) : DEFAULT_MAX_TRIPLES;

        String label = Neo4jUtil.kbLabel(knowledgeId);
        var tracker = new TaskProgressTracker(taskService, task.getId())
                .phases("加载社区", "生成摘要", "向量化摘要", "收尾");

        log.info("[社区摘要] 开始, taskId={}, knowledgeId={}, concurrency={}", task.getId(), knowledgeId, concurrency);

        Long actualProviderId = providerId != null ? providerId : resolveDefaultProviderId();

        try {
            // 1. 加载社区列表
            tracker.nextPhase("正在加载社区列表...");
            List<Integer> communityIds = loadCommunityIds(label);
            if (communityIds.isEmpty()) {
                throw new RuntimeException("未检测到任何社区，请先执行「社区检测」任务");
            }
            checkCancel(task);
            log.info("[社区摘要] 待处理社区数={}", communityIds.size());

            // 2. 并发生成摘要
            tracker.nextPhase("正在生成社区摘要...");
            var progress = tracker.subRange(25, 80, communityIds.size());
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);

            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>(communityIds.size());
            try {
                for (int cid : communityIds) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        if (redisUtil != null && redisUtil.hasCancelSignal(task.getId())) {
                            throw new CompletionException(new TaskCancelledException());
                        }
                        try {
                            String summary = summarizeCommunity(label, cid, actualProviderId, modelId, modelParams,
                                    maxEntities, maxTriples);
                            if (summary == null || summary.isBlank() || INSUFFICIENT.equals(summary)) {
                                log.debug("[社区摘要] 社区 {} 信息不足或未产出摘要，跳过写入", cid);
                                failed.incrementAndGet();
                            } else {
                                writeSummary(label, cid, summary);
                                success.incrementAndGet();
                            }
                        } catch (Exception e) {
                            if (ModelErrorClassifier.isFatal(e)) {
                                throw new CompletionException(ModelErrorClassifier.toRuntimeException(e));
                            }
                            log.warn("[社区摘要] 社区 {} 摘要生成失败: {}", cid, ModelErrorClassifier.formatDetail(e));
                            failed.incrementAndGet();
                        }
                        progress.tick("社区摘要完成 %d/%d".formatted(success.get(), communityIds.size()));
                    }, pool);
                    futures.add(future);
                }
                joinAll(futures);
            } finally {
                pool.shutdownNow();
            }

            if (success.get() == 0) {
                throw new RuntimeException("全部社区摘要生成失败，请检查 LLM 模型配置与连通性");
            }

            // 3. 摘要向量化（把高层语料变成可检索向量，否则全局检索无从召回）
            tracker.nextPhase("正在向量化社区摘要...");
            int embedded = 0;
            try {
                embedded = embedCommunitySummaries(knowledgeId, label);
            } catch (Exception e) {
                // 向量化属于增强能力：失败只在结果里体现，不回滚已写入 Neo4j 的摘要
                log.warn("[社区摘要] 摘要向量化失败（已生成的摘要不受影响）: {}", e.getMessage());
            }

            // 4. 收尾统计
            tracker.nextPhase("正在收尾...");
            tracker.update(100, "社区摘要生成完成");
            String result = "社区摘要生成完成, knowledgeId=%d, 成功=%d, 跳过/失败=%d, 总社区=%d, 摘要向量化=%d"
                    .formatted(knowledgeId, success.get(), failed.get(), communityIds.size(), embedded);
            log.info("[社区摘要] {}", result);
            return result;

        } catch (TaskCancelledException e) {
            log.info("[社区摘要] 任务已取消: taskId={}", task.getId());
            throw e;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() instanceof RuntimeException ? e.getCause() : e;
            if (cause instanceof TaskCancelledException cancelled) {
                log.info("[社区摘要] 任务已取消: taskId={}", task.getId());
                throw cancelled;
            }
            throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
        } catch (Exception e) {
            log.error("[社区摘要] 失败, taskId={}, knowledgeId={}, error={}", task.getId(), knowledgeId, e.getMessage(), e);
            throw e;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  单个社区的摘要生成
    // ────────────────────────────────────────────────────────────

    /**
     * 为一个社区生成摘要。
     *
     * @return 摘要文本；返回 {@code null} 表示无法生成（社区无成员）
     */
    private String summarizeCommunity(String label, int cid, Long providerId, String modelId,
                                      Map<String, Object> modelParams, int maxEntities, int maxTriples) {
        List<String> members = loadMembers(label, cid, maxEntities);
        if (members.isEmpty()) {
            return null;
        }
        List<String[]> relations = loadRelations(label, cid, maxTriples);

        // 总数可能大于展示数，用 suffix 明确告知 LLM 这是截断后的内容
        String memberSuffix = members.size() >= maxEntities ? "，以下仅列出部分" : "";
        StringBuilder relBuilder = new StringBuilder();
        for (String[] r : relations) {
            relBuilder.append(r[0]).append(" -").append(r[1]).append("-> ").append(r[2]).append('\n');
        }
        String relText = relBuilder.isEmpty() ? "（该社区内部未检出明确关系）" : relBuilder.toString().strip();
        String relSuffix = relations.size() >= maxTriples ? "，以下仅列出部分" : "";

        String userPrompt = SUMMARY_USER_PROMPT.formatted(
                members.size(), memberSuffix, String.join("、", members),
                relations.size(), relSuffix, relText);

        var ctx = modelFactory.getModelWithContext(providerId, modelId, modelParams);
        List<Msg> messages = List.of(Msgs.system(SUMMARY_SYSTEM_PROMPT), Msgs.user(userPrompt));
        var response = ctx.call(messages);
        return cleanSummary(Msgs.extractText(response));
    }

    /** 清洗 LLM 输出：剥离 markdown 包裹与多余空白 */
    private String cleanSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(markdown|md|text)?\\s*", "").replaceAll("\\s*```$", "");
        }
        text = text.replaceAll("^[\\s　]+|[\\s　]+$", "");
        return text.isBlank() ? null : text;
    }

    // ────────────────────────────────────────────────────────────
    //  Neo4j 读写
    // ────────────────────────────────────────────────────────────

    /** 加载全部社区 id，按 member_count 降序（先摘要信息量大的社区） */
    private List<Integer> loadCommunityIds(String label) {
        List<Record> records = neo4jUtil.query("""
                MATCH (c:Community:`%s`)
                RETURN c.community_id AS cid
                ORDER BY c.member_count DESC
                """.formatted(label), Map.of());

        List<Integer> ids = new ArrayList<>(records.size());
        for (Record r : records) {
            Integer cid = r.get("cid").asInt(-1);
            if (cid >= 0) {
                ids.add(cid);
            }
        }
        return ids;
    }

    /** 加载社区的成员实体名（截断到 limit） */
    private List<String> loadMembers(String label, int cid, int limit) {
        List<Record> records = neo4jUtil.query("""
                MATCH (n:Entity:`%s`)
                WHERE n.community_id = $cid
                RETURN n.name AS name
                LIMIT $limit
                """.formatted(label), Map.of("cid", cid, "limit", limit));

        List<String> names = new ArrayList<>(records.size());
        for (Record r : records) {
            String name = r.get("name").asString(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 加载社区内部的关系三元组（截断到 limit） */
    private List<String[]> loadRelations(String label, int cid, int limit) {
        List<Record> records = neo4jUtil.query("""
                MATCH (a:Entity:`%s`)-[r:RELATION]->(b:Entity:`%s`)
                WHERE a.community_id = $cid AND b.community_id = $cid
                RETURN a.name AS head, r.relation_type AS relation, b.name AS tail
                LIMIT $limit
                """.formatted(label, label), Map.of("cid", cid, "limit", limit));

        List<String[]> rels = new ArrayList<>(records.size());
        for (Record r : records) {
            String head = r.get("head").asString(null);
            String relation = r.get("relation").asString(null);
            String tail = r.get("tail").asString(null);
            if (head != null && tail != null) {
                rels.add(new String[]{head, relation != null ? relation : "相关", tail});
            }
        }
        return rels;
    }

    /** 回写社区摘要到 Community 节点 */
    private void writeSummary(String label, int cid, String summary) {
        neo4jUtil.run("""
                MATCH (c:Community:`%s` {community_id: $cid})
                SET c.summary = $summary,
                    c.summarized_at = timestamp()
                """.formatted(label), Map.of("cid", cid, "summary", summary));
    }

    // ────────────────────────────────────────────────────────────
    //  摘要向量化（GraphRAG 第 3 步 · 写侧）
    // ────────────────────────────────────────────────────────────

    /**
     * 把已写入 Neo4j 的社区摘要向量化，写入 Milvus Community Collection。
     * <p>这一步是「社区摘要能被检索到」的前提：摘要只有文本存在图里时，
     * 检索侧没有任何向量可以用来做语义匹配。</p>
     *
     * <p>降级策略：Embedding / Milvus 不可用时记 WARN 并返回 0，不抛异常，
     * 保证「生成摘要」这个主目标不受影响。</p>
     *
     * @return 成功写入向量的社区数
     */
    private int embedCommunitySummaries(Long knowledgeId, String label) {
        if (textEmbeddingService == null) {
            log.warn("[社区摘要] 未配置 Embedding 服务，跳过摘要向量化（全局检索不可用）: knowledgeId={}", knowledgeId);
            return 0;
        }
        if (milvusUtil == null || !milvusUtil.isAvailable()) {
            log.warn("[社区摘要] Milvus 不可用，跳过摘要向量化（全局检索不可用）: knowledgeId={}", knowledgeId);
            return 0;
        }

        List<Record> records = neo4jUtil.query("""
                MATCH (c:Community:`%s`)
                WHERE c.summary IS NOT NULL AND c.summary <> ''
                RETURN c.community_id AS cid, c.summary AS summary
                ORDER BY c.member_count DESC
                """.formatted(label), Map.of());

        List<Integer> ids = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (Record r : records) {
            String summary = r.get("summary").asString(null);
            if (summary == null || summary.isBlank() || INSUFFICIENT.equals(summary.strip())) {
                continue;
            }
            ids.add(r.get("cid").asInt(-1));
            contents.add(summary.strip());
        }
        if (contents.isEmpty()) {
            log.info("[社区摘要] 无有效摘要可向量化: knowledgeId={}", knowledgeId);
            return 0;
        }

        // 幂等：摘要重算后必须先清旧向量，否则检索会命中上一轮过期语料
        if (milvusUtil.hasCommunityCollection(knowledgeId)) {
            milvusUtil.dropCommunityCollection(knowledgeId);
        }

        List<float[]> vectors = batchEmbed(contents);
        List<Integer> okIds = new ArrayList<>();
        List<String> okContents = new ArrayList<>();
        List<float[]> okVectors = new ArrayList<>();
        for (int k = 0; k < vectors.size(); k++) {
            float[] v = vectors.get(k);
            if (v == null) {
                log.warn("[社区摘要] 摘要嵌入失败, 跳过: communityId={}", ids.get(k));
                continue;
            }
            okIds.add(ids.get(k));
            okContents.add(contents.get(k));
            okVectors.add(v);
        }
        if (okVectors.isEmpty()) {
            log.warn("[社区摘要] 摘要向量全部生成失败, 跳过写入: knowledgeId={}", knowledgeId);
            return 0;
        }

        milvusUtil.createCommunityCollection(knowledgeId, okVectors.get(0).length);
        milvusUtil.insertCommunityVectors(knowledgeId, okIds, okContents, okVectors);
        log.info("[社区摘要] 摘要向量写入 Milvus: knowledgeId={}, count={}", knowledgeId, okIds.size());
        return okIds.size();
    }

    /**
     * 批量文本嵌入（失败项返回 null 占位，由调用方逐条过滤，绝不写零向量）
     */
    private List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty() || textEmbeddingService == null) {
            return List.of();
        }
        int batchSize = 16;
        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            try {
                List<double[]> batchVectors = textEmbeddingService.embedBatch(batch);
                for (int j = 0; j < batchVectors.size(); j++) {
                    double[] vec = batchVectors.get(j);
                    if (vec == null) {
                        all.add(null);
                        continue;
                    }
                    float[] fVec = new float[vec.length];
                    for (int k = 0; k < vec.length; k++) {
                        fVec[k] = (float) vec[k];
                    }
                    all.add(fVec);
                }
            } catch (Exception e) {
                log.warn("[社区摘要] 整批摘要嵌入失败(已隔离, 不写零向量): batch=[{}, {}), error={}", i, end, e.getMessage());
                for (int j = 0; j < batch.size(); j++) {
                    all.add(null);
                }
            }
        }
        return all;
    }

    // ────────────────────────────────────────────────────────────
    //  工具
    // ────────────────────────────────────────────────────────────

    /** payload 未指定 provider 时，回落到第一个可用模型提供商（与 GraphExtractor 一致的降级策略） */
    private Long resolveDefaultProviderId() {
        List<Long> availableIds = modelFactory.getAvailableProviderIds();
        if (availableIds.isEmpty()) {
            throw new IllegalStateException("没有可用的模型提供商，请先配置模型");
        }
        return availableIds.get(0);
    }

    private Long resolvePayloadLong(JsonNode payload, String field) {
        if (!payload.has(field) || payload.get(field).isNull()) {
            return null;
        }
        return payload.get(field).asLong();
    }

    private Map<String, Object> parseModelParams(JsonNode payload) {
        if (payload.has("modelParams") && payload.get("modelParams").isObject()) {
            try {
                return objectMapper.convertValue(payload.get("modelParams"),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception ignored) {
                // 模型参数解析失败不应阻断主流程，降级为默认参数
            }
        }
        return null;
    }

    /** 等待全部任务结束，期间若任一任务抛异常则在此处重新抛出 */
    private void joinAll(List<CompletableFuture<Void>> futures) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /** 检测到取消信号立即中断 */
    private void checkCancel(Task task) {
        if (redisUtil != null && redisUtil.hasCancelSignal(task.getId())) {
            throw new TaskCancelledException();
        }
    }
}
