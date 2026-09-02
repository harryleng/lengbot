-- ============================================================
-- 增量迁移：embedding 表补 knowledge_id，让 HNSW 向量索引真正生效
-- 日期：2026-09-02
--
-- 背景：
--   向量检索 SQL 的过滤条件此前落在 chunk/qa_pair 表（WHERE c.knowledge_id = ?），
--   而 ORDER BY e.vector <=> ? 依赖 embedding 表的 HNSW 索引。
--   由于过滤列与索引列跨表，PostgreSQL planner 无法在遍历 HNSW 的同时做过滤，
--   只能退化为全表暴力余弦排序，HNSW 索引形同虚设。
--
-- 方案（pgvector 官方 pre-filter + HNSW 模式）：
--   在 embedding 表冗余 knowledge_id，过滤条件直接落到 embedding 表，
--   planner 先用 btree 索引过滤出该库的向量，再在结果集上用 HNSW 做近邻搜索。
--
-- 执行前请确认：
--   1. 已在测试/预发环境验证
--   2. embedding 表数据量可控（当前 ~160 行，回填秒级完成）
-- ============================================================

BEGIN;

-- 1. 加列（冗余 knowledge_id）
ALTER TABLE embedding ADD COLUMN IF NOT EXISTS knowledge_id BIGINT;

-- 2. 回填：chunk 型向量取 chunk.knowledge_id；qa_pair 型向量取 qa_pair.knowledge_id
UPDATE embedding e
SET knowledge_id = c.knowledge_id
FROM chunk c
WHERE e.chunk_id = c.id
  AND e.knowledge_id IS NULL;

UPDATE embedding e
SET knowledge_id = qp.knowledge_id
FROM qa_pair qp
WHERE e.qa_pair_id = qp.id
  AND e.knowledge_id IS NULL;

-- 3. 兜底：仍为 NULL 的行（孤儿向量，理论上不应存在）——置 0 以便建 NOT NULL 约束
--    实际上当前数据应已全部回填，此步仅防御性处理
-- UPDATE embedding SET knowledge_id = 0 WHERE knowledge_id IS NULL;

-- 4. 建 btree 过滤索引（HNSW 的前置过滤列，pre-filter 模式的关键）
CREATE INDEX IF NOT EXISTS idx_embedding_knowledge_id ON embedding (knowledge_id);

-- 5. 收尾：若需强制 NOT NULL 约束（可选，建议数据稳定后再加）
-- ALTER TABLE embedding ALTER COLUMN knowledge_id SET NOT NULL;

COMMIT;
