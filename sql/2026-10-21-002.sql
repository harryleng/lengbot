-- =============================================================================
-- 2026-10-21-002：llm_trace 增加 first_token_ms（首字延迟 / TTFT）
-- =============================================================================
-- 背景：
--   llm_trace 现有 total_duration_ms 只能反映整轮耗时，无法回答"用户多久看到第一个字"。
--   而 SSE 流式场景下，用户体感几乎完全由 TTFT 决定 —— 这是 AI 应用最核心的体验指标。
--
-- 口径：
--   first_token_ms = 首个可见内容增量抵达内核的时刻 - 请求进入 streamCore 的时刻
--   即从 OAuth 鉴权之后的整个链路（会话解析 / Agent 加载 / 消息组装 / 记忆注入 /
--   上下文摘要 / 工具装配 / 模型首包）全部计入，等同于用户真实感知到的首字延迟。
--
--   取 getting t0 = ctx.startTime（在 streamCore 首行设置，位于中间件链执行之后），
--   因此本指标 = 模型侧 TTFT + 中间件链准备耗时。
--
-- 取值说明：
--   0     = 未产生任何可见内容（例如被用户输入敏感词拦截、Workflow 短路、正文为空）
--   >0    = 正常测得的首字延迟（毫秒）
--
-- =============================================================================

ALTER TABLE llm_trace
    ADD COLUMN IF NOT EXISTS first_token_ms BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN llm_trace.first_token_ms IS
    '首字延迟(毫秒)：请求进入对话内核到首个可见内容增量的耗时；0 表示本轮无可见内容';

-- 常用查询 1：按模型看 P50 / P95 首字延迟
-- SELECT model,
--        count(*) AS n,
--        percentile_cont(0.5)  WITHIN GROUP (ORDER BY first_token_ms) AS p50_ms,
--        percentile_cont(0.95) WITHIN GROUP (ORDER BY first_token_ms) AS p95_ms
-- FROM llm_trace
-- WHERE status = 'completed' AND first_token_ms > 0
-- GROUP BY model ORDER BY p95_ms DESC;

-- 常用查询 2：把 TTFT 拆成「模型前准备」和「模型侧首字」两段
-- 原理：llm_call span 的 startTime 是模型调用开始时刻，减去 same-trace 的请求起点（此处用
-- 最早 span 的 startTime 近似）得到准备耗时，再用 first_token_ms 减去它即为模型侧 TTFT。
-- SELECT s.id,
--        s.first_token_ms                                          AS ttft_ms,
--        MIN(sp.start_time)                                        AS req_start,
--        MIN(CASE WHEN sp.name = 'llm_call' THEN sp.start_time END) AS llm_start,
--        MIN(CASE WHEN sp.name = 'llm_call' THEN sp.start_time END) - MIN(sp.start_time) AS prep_ms,
--        s.first_token_ms
--          - (MIN(CASE WHEN sp.name = 'llm_call' THEN sp.start_time END) - MIN(sp.start_time)) AS model_ttft_ms
-- FROM llm_trace s
-- JOIN LATERAL jsonb_to_recordset(s.spans)
--        AS sp(name text, start_time bigint, duration_ms bigint) ON TRUE
-- WHERE s.first_token_ms > 0
-- GROUP BY s.id, s.first_token_ms
-- ORDER BY model_ttft_ms DESC
-- LIMIT 20;
