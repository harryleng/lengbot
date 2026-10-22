-- ============================================================
-- 2026-10-21-001：会话上下文摘要缓存表
--
-- 背景：
--   原 summarizeIfNeeded() 每轮对话都同步调用一次 LLM 生成上下文摘要，
--   且结果只存在内存 List 里、不落库 —— 长会话一旦体积超过阈值（默认 100KB），
--   **每轮**首字延迟都要额外叠加一次完整摘要调用的耗时（可达数秒）。
--   更糟的是该调用走 LlmTraceContext.callWithoutTrace，llm_trace 里完全看不到。
--
-- 方案：
--   摘要结果落库 + 缓存复用 + 异步刷新。
--   upto_message_id 记录「摘要覆盖到哪条消息」，下一轮直接复用旧摘要，
--   只把 upto 之后的新消息接在后面；累积新消息再次超阈值时才重新生成，
--   且此时有旧摘要兜底，可放到后台线程异步刷新，用户全程无感。
--
-- 效果：
--   首字前的同步 LLM 调用从「每轮一次」降到「首次一次」，
--   稳态下 buildMessages 阶段不再有任何 LLM 调用。
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_session_summary (
    id              BIGINT          NOT NULL,
    session_id      BIGINT          NOT NULL,
    upto_message_id BIGINT          NOT NULL,
    covered_count   INT             NOT NULL DEFAULT 0,
    covered_chars   INT             NOT NULL DEFAULT 0,
    summary         TEXT            NOT NULL,
    model           VARCHAR(128),
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 一个会话只保留一份摘要（滚动覆盖）
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_summary_session
    ON chat_session_summary (session_id);

-- 按覆盖位置检索，便于排查摘要新鲜度
CREATE INDEX IF NOT EXISTS idx_session_summary_upto
    ON chat_session_summary (upto_message_id);

COMMENT ON TABLE chat_session_summary IS '会话上下文摘要缓存（避免长会话每轮同步生成摘要阻塞首字）';
COMMENT ON COLUMN chat_session_summary.session_id IS '会话ID';
COMMENT ON COLUMN chat_session_summary.upto_message_id IS '摘要覆盖到的最后一条消息ID';
COMMENT ON COLUMN chat_session_summary.covered_count IS '摘要覆盖的消息条数';
COMMENT ON COLUMN chat_session_summary.covered_chars IS '摘要覆盖的原始字符数';
COMMENT ON COLUMN chat_session_summary.summary IS '摘要正文';
COMMENT ON COLUMN chat_session_summary.model IS '生成摘要所用的模型标识';

-- ============================================================
-- 部署后校验（只读）
-- ============================================================
-- SELECT session_id, upto_message_id, covered_count, covered_chars,
--        left(summary, 60) AS summary_head, update_time
-- FROM chat_session_summary ORDER BY update_time DESC LIMIT 10;
