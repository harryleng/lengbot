-- ============================================================================
-- Source: 2026-10-19-001.sql
-- 轮次检查点表（Checkpointer 风格，支持 Agent 运行崩溃续跑与时间旅行回放）
-- 适用：PostgreSQL 15
-- 幂等：使用 IF NOT EXISTS，可重复执行；新环境已并入 init.sql 快照
-- ============================================================================

CREATE TABLE IF NOT EXISTS round_checkpoint (
    id              BIGINT          NOT NULL,
    run_id          VARCHAR(64)     NOT NULL,
    session_id      BIGINT,
    user_id         BIGINT,
    agent_id        BIGINT,
    workspace_id    BIGINT,
    depth           INTEGER         NOT NULL DEFAULT 0,
    phase           VARCHAR(32)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    input_tokens    INTEGER         NOT NULL DEFAULT 0,
    output_tokens   INTEGER         NOT NULL DEFAULT 0,
    cached_tokens   INTEGER         NOT NULL DEFAULT 0,
    aborted         BOOLEAN         NOT NULL DEFAULT FALSE,
    abort_reason    VARCHAR(64),
    state_json      TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_round_checkpoint_run ON round_checkpoint (run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_round_checkpoint_session ON round_checkpoint (session_id, created_at DESC);
