-- ============================================================================
-- Source: 2026-09-01-001.sql
-- 任务表（task）索引优化：消除列表查询与僵尸任务扫描的 filesort
-- 适用：PostgreSQL 15（存量环境直接执行本文件；新环境已并入 init.sql 快照）
-- 幂等：全部使用 IF NOT EXISTS / IF EXISTS，可重复执行
-- ============================================================================

-- 背景
-- ----------------------------------------------------------------------------
-- TaskServiceImpl.listByUserId 生成的 SQL 形如：
--   SELECT * FROM task
--    WHERE user_id = ?
--      [AND status = ? | AND status IN ('pending','pending_retry','running')]
--      [AND type = ?] [AND name LIKE '%关键词%']
--    ORDER BY create_time DESC LIMIT ? OFFSET ?
--
-- 原有索引只有三个单列索引（idx_task_user_id / idx_task_status / idx_task_type），
-- 只能用于过滤。ORDER BY create_time DESC 必须回表后在内存做 filesort，
-- 且深分页时要先扫完该用户的全部匹配行再排序，代价随数据量线性上升。
--
-- 把排序列并入索引后，B+ 树叶子层本身即按 (前缀, create_time DESC) 有序，
-- 排序步骤被消除，读满 LIMIT 即可提前终止。

-- 1. 核心：用户维度等值过滤 + 时间倒序
--    最左前缀原则：等值列在前，排序列在后
CREATE INDEX IF NOT EXISTS idx_task_user_create_time
    ON task (user_id, create_time DESC);

-- 2. 列表页带状态筛选
--    注意：status 为单值等值时可直接命中；status IN (多值) 无法给出全局有序
--    结果，仍会走 Sort 节点（索引的固有局限，非写法问题）
CREATE INDEX IF NOT EXISTS idx_task_user_status_time
    ON task (user_id, status, create_time DESC);

-- 3. 僵尸任务扫描（TaskZombieScheduler）
--    该查询不带 user_id，按 status 过滤后按 update_time 升序取最老一批，
--    原单列 idx_task_status 只能过滤不能排序，故升级为复合索引而非删除
CREATE INDEX IF NOT EXISTS idx_task_status_update_time
    ON task (status, update_time);

-- 4. 清理冗余索引
--    idx_task_status：已被 idx_task_status_update_time 的最左前缀完全覆盖
DROP INDEX IF EXISTS idx_task_status;
--    idx_task_type：全项目 type 过滤均带 user_id，无独立按 type 的查询，
--    由 user_id 前缀的复合索引覆盖（保留它只会拖慢写入）
DROP INDEX IF EXISTS idx_task_type;

COMMENT ON INDEX idx_task_user_create_time IS '用户任务列表：user_id 过滤 + create_time 倒序，消除 filesort';
COMMENT ON INDEX idx_task_user_status_time IS '用户任务列表带状态筛选：user_id + status 过滤 + create_time 倒序';
COMMENT ON INDEX idx_task_status_update_time IS '僵尸任务调度：status 过滤 + update_time 升序（不带 user_id）';

-- ============================================================================
-- 可选：任务名模糊搜索（默认不启用）
-- ----------------------------------------------------------------------------
-- listByUserId 中的 name LIKE '%关键词%' 带前置通配符，B+ 树最左前缀失效，
-- 完全走不了索引，必须用 pg_trgm + GIN。
-- 但 task 是高频写入的任务队列表，GIN 索引维护成本较高；任务名搜索目前只在
-- 用户主动输入关键词时触发，并非高频路径，故默认不建。
-- 若后续该搜索成为瓶颈，再执行：
--
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX idx_task_name_trgm ON task USING GIN (name gin_trgm_ops);
--
-- 注：pg_trgm 为 trusted extension，数据库 owner 即可创建。
-- ============================================================================

-- ============================================================================
-- 验证方式（执行后确认 Sort 节点消失）
-- ----------------------------------------------------------------------------
--   EXPLAIN ANALYZE
--   SELECT * FROM task
--    WHERE user_id = 1
--    ORDER BY create_time DESC
--    LIMIT 20;
--
-- 期望：Index Scan using idx_task_user_create_time，且计划中无 Sort 节点。
-- 若出现 Seq Scan，说明表数据量尚小或统计信息过期，执行 ANALYZE task; 后重试。
-- ============================================================================
