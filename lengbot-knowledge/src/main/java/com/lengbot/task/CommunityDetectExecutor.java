package com.lengbot.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.task.TaskCancelledException;
import com.lengbot.entity.Task;
import com.lengbot.service.TaskService;
import com.lengbot.util.Neo4jUtil;
import com.lengbot.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社区检测任务执行器（GraphRAG「社区总结」链路 · 第 1 步）
 *
 * <p><b>作用</b>：对已完成图谱抽取的知识库，用弱连通分量（Weakly Connected Component）
 * 把实体切成若干「社区」，为实体写回 {@code community_id}，并生成 {@code Community} 节点。
 *
 * <p><b>为什么用连通分量而不是 Leiden/Louvain</b>：本机 Neo4j 为社区版、未安装 GDS 插件，
 * 无法直接调用图算法库。连通分量是零依赖的近似分群（方案 A），先把整条链路跑通；
 * 后续若要更贴近微软 GraphRAG 的社区质量，只需把 {@link #computeGroups} 换成 Louvain 实现，
 * 其余链路（写回标签、建 Community 节点、后续的步骤 2/3）无需改动。
 *
 * <p><b>数据契约（供后续步骤消费）</b>：
 * <ul>
 *   <li>实体节点 {@code (n:Entity:kb_{knowledgeId})} 新增整数属性 {@code community_id}（从 0 起）</li>
 *   <li>社区节点 {@code (c:Community:kb_{knowledgeId})} 记录 {@code community_id}、{@code member_count}</li>
 *   <li>孤立实体（无任何关系）不分配 community_id，不进任何社区 —— 它们对摘要没有信息量</li>
 * </ul>
 *
 * <p><b>注意</b>：Community 节点不带 {@code Entity} 标签，因此不会被图谱抽取的
 * {@code MATCH (n:Entity:kb_x) ... DETACH DELETE} 逻辑误删，但也不会随之自动清理；
 * 本执行器每次运行前会主动清理旧产物，保证幂等可重跑。
 *
 * @author lw
 * @since 2026-10-10
 */
@Slf4j
@Component("communityDetectExecutor")
@RequiredArgsConstructor
public class CommunityDetectExecutor implements TaskExecutor {

    /** 单次检测最多加载的边数，超出即截断并告警，防止超大图撑爆内存 */
    private static final int MAX_EDGES = 500_000;

    /** 写回 community_id 时每批处理的实体数 */
    private static final int WRITE_BATCH_SIZE = 500;

    private final Neo4jUtil neo4jUtil;
    private final TaskService taskService;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Override
    public String execute(Task task) throws Exception {
        if (!neo4jUtil.isAvailable()) {
            throw new RuntimeException("Neo4j 图数据库不可用，无法执行社区检测");
        }

        JsonNode payload = objectMapper.readTree(task.getPayload());
        if (payload == null || !payload.has("knowledgeId")) {
            throw new RuntimeException("任务 payload 缺少 knowledgeId");
        }
        Long knowledgeId = payload.get("knowledgeId").asLong();
        String label = Neo4jUtil.kbLabel(knowledgeId);

        var tracker = new TaskProgressTracker(taskService, task.getId())
                .phases("清理旧社区", "加载图数据", "计算连通分量", "写回社区标识", "建立社区节点");

        log.info("[社区检测] 开始, taskId={}, knowledgeId={}, label={}", task.getId(), knowledgeId, label);

        try {
            // 1. 清理上一次检测的产物（幂等，支持反复重跑）
            tracker.nextPhase("正在清理旧社区数据...");
            clearOldCommunities(label);

            // 2. 加载实体与关系
            tracker.nextPhase("正在加载图数据...");
            Set<String> entities = loadEntities(label);
            if (entities.isEmpty()) {
                throw new RuntimeException("图谱为空，没有可检测的实体，请先执行图谱抽取");
            }
            checkCancel(task);

            List<String[]> edges = loadEdges(label);
            if (edges.size() >= MAX_EDGES) {
                log.warn("[社区检测] 边数达到上限 {} 已被截断，社区划分结果不完整", MAX_EDGES);
            }
            log.info("[社区检测] 图数据加载完成: entities={}, edges={}", entities.size(), edges.size());

            // 3. 弱连通分量分群
            tracker.nextPhase("正在计算连通分量...");
            List<List<String>> communities = computeGroups(entities, edges);
            checkCancel(task);

            if (communities.isEmpty()) {
                tracker.update(100, "社区检测完成");
                String msg = "社区检测完成, knowledgeId=%d, 未发现含关系的社区（孤立实体 %d 个）"
                        .formatted(knowledgeId, entities.size());
                log.warn("[社区检测] {}", msg);
                return msg;
            }

            // 4. 写回实体的 community_id
            tracker.nextPhase("正在写回社区标识...");
            writeCommunityIds(label, communities);
            checkCancel(task);

            // 5. 建立 Community 节点
            tracker.nextPhase("正在建立社区节点...");
            writeCommunityNodes(label, communities);

            int coveredEntity = communities.stream().mapToInt(List::size).sum();
            int largest = communities.get(0).size();
            double largestRatio = largest * 100.0 / entities.size();
            if (largestRatio > 30.0) {
                log.warn("[社区检测] 最大社区占比异常偏高: {}/{} = {}%，图中可能存在 hub 型通用实体" +
                                "把大量节点粘连成一团，建议后续升级为 Louvain/Leiden 以收紧社区边界",
                        largest, entities.size(), String.format("%.1f", largestRatio));
            }

            tracker.update(100, "社区检测完成");
            String result = "社区检测完成, knowledgeId=%d, 社区数=%d, 覆盖实体=%d/%d, 最大社区=%d(%.1f%%)"
                    .formatted(knowledgeId, communities.size(), coveredEntity, entities.size(), largest, largestRatio);
            log.info("[社区检测] {}", result);
            return result;

        } catch (TaskCancelledException e) {
            log.info("[社区检测] 任务已取消: taskId={}", task.getId());
            throw e;
        } catch (Exception e) {
            log.error("[社区检测] 失败, taskId={}, knowledgeId={}, error={}", task.getId(), knowledgeId, e.getMessage(), e);
            throw e;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  核心算法：弱连通分量分群
    // ────────────────────────────────────────────────────────────

    /**
     * 分群：把有边相连的实体归入同一社区。
     *
     * <p>后续升级 Louvain 时替换此方法即可 —— 入参是「实体名集合 + 边集合」，
     * 出参统一是「按成员数降序排列的成员列表集合」，第 4/5 步不受影响。
     *
     * @param entities 全部实体名
     * @param edges    无向边（每条为 [源实体名, 目标实体名]）
     * @return 社区列表，已按成员数降序；仅保留成员数 &gt;= 2 的社区
     */
    private List<List<String>> computeGroups(Set<String> entities, List<String[]> edges) {
        UnionFind uf = new UnionFind();
        for (String[] e : edges) {
            uf.union(e[0], e[1]);
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String name : entities) {
            groups.computeIfAbsent(uf.find(name), k -> new ArrayList<>()).add(name);
        }

        return groups.values().stream()
                // 孤立点自己就是一个大小为 1 的分量，对摘要无信息量，丢弃
                .filter(members -> members.size() >= 2)
                .map(members -> {
                    List<String> sorted = new ArrayList<>(members);
                    Collections.sort(sorted);
                    return sorted;
                })
                .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                .toList();
    }

    // ────────────────────────────────────────────────────────────
    //  Neo4j 读写
    // ────────────────────────────────────────────────────────────

    /** 清理旧的 Community 节点与实体上的 community_id（保证重复运行幂等） */
    private void clearOldCommunities(String label) {
        neo4jUtil.run("""
                MATCH (c:Community:`%s`)
                DETACH DELETE c
                """.formatted(label), Map.of());

        neo4jUtil.run("""
                MATCH (n:Entity:`%s`)
                WHERE n.community_id IS NOT NULL
                REMOVE n.community_id
                """.formatted(label), Map.of());
    }

    /** 加载知识库下的全部实体名 */
    private Set<String> loadEntities(String label) {
        List<Record> records = neo4jUtil.query("""
                MATCH (n:Entity:`%s`)
                RETURN n.name AS name
                """.formatted(label), Map.of());

        Set<String> names = new HashSet<>();
        for (Record r : records) {
            String name = r.get("name").asString(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 加载无向边，每条边只保留一次（用 elementId 排序去重，避免 a-b 与 b-a 重复入图）。
     */
    private List<String[]> loadEdges(String label) {
        List<Record> records = neo4jUtil.query("""
                MATCH (a:Entity:`%s`)-[r:RELATION]-(b:Entity:`%s`)
                WHERE elementId(a) < elementId(b)
                RETURN DISTINCT a.name AS src, b.name AS dst
                LIMIT %d
                """.formatted(label, label, MAX_EDGES), Map.of());

        List<String[]> edges = new ArrayList<>(records.size());
        for (Record r : records) {
            String src = r.get("src").asString(null);
            String dst = r.get("dst").asString(null);
            if (src != null && dst != null) {
                edges.add(new String[]{src, dst});
            }
        }
        return edges;
    }

    /** 批量写回实体的 community_id（第 cid 个社区的所有成员标记为 cid） */
    private void writeCommunityIds(String label, List<List<String>> communities) {
        String cypher = """
                UNWIND $items AS item
                MATCH (n:Entity:`%s` {name: item.name})
                SET n.community_id = item.cid
                """.formatted(label);

        List<Map<String, Object>> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        for (int cid = 0; cid < communities.size(); cid++) {
            for (String name : communities.get(cid)) {
                Map<String, Object> item = new HashMap<>(2);
                item.put("name", name);
                item.put("cid", cid);
                batch.add(item);
                if (batch.size() >= WRITE_BATCH_SIZE) {
                    neo4jUtil.run(cypher, Map.of("items", batch));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            neo4jUtil.run(cypher, Map.of("items", batch));
        }
    }

    /** 为每个社区建立 Community 元信息节点（幂等 MERGE） */
    private void writeCommunityNodes(String label, List<List<String>> communities) {
        String cypher = """
                MERGE (c:Community:`%s` {community_id: $cid})
                SET c.member_count = $count,
                    c.updated_at = timestamp()
                """.formatted(label);

        for (int cid = 0; cid < communities.size(); cid++) {
            Map<String, Object> params = new HashMap<>(2);
            params.put("cid", cid);
            params.put("count", communities.get(cid).size());
            neo4jUtil.run(cypher, params);
        }
    }

    /** 检测到取消信号立即中断 */
    private void checkCancel(Task task) {
        if (redisUtil != null && redisUtil.hasCancelSignal(task.getId())) {
            throw new TaskCancelledException();
        }
    }

    // ────────────────────────────────────────────────────────────
    //  并查集（Union-Find）
    // ────────────────────────────────────────────────────────────

    /**
     * 并查集：路径压缩 + 按秩合并，用于求弱连通分量。
     * 成员以实体名为键（图中实体以 name 唯一 MERGE，故 name 可直接作为标识）。
     */
    private static class UnionFind {

        private final Map<String, String> parent = new HashMap<>();
        private final Map<String, Integer> rank = new HashMap<>();

        /** 查找根节点（迭代实现 + 路径压缩，避免深链递归导致栈溢出） */
        private String find(String x) {
            parent.putIfAbsent(x, x);

            String root = x;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            String cur = x;
            while (!cur.equals(root)) {
                String next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        private void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (ra.equals(rb)) {
                return;
            }
            int rka = rank.getOrDefault(ra, 0);
            int rkb = rank.getOrDefault(rb, 0);
            if (rka < rkb) {
                parent.put(ra, rb);
            } else if (rka > rkb) {
                parent.put(rb, ra);
            } else {
                parent.put(rb, ra);
                rank.put(ra, rka + 1);
            }
        }
    }
}
