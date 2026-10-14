-- Tier 1 工作流检查点（每节点持久化，进程崩溃/重启可续跑）
-- 与 LangGraph PostgresSaver 对齐的最小可用版：每节点执行完写一行，崩溃后按 run_id 取最新检查点前向续跑。
-- 注意：此表为「运行态快照」，非审计日志；孤儿 RUNNING 由后端启动自愈扫描回收。
CREATE TABLE IF NOT EXISTS workflow_checkpoint (
    id               BIGINT       NOT NULL,
    run_id           VARCHAR(64)  NOT NULL,
    agent_id         BIGINT       NOT NULL,
    session_id       BIGINT,
    step_index       INT          NOT NULL DEFAULT 0,
    current_node_id  VARCHAR(64),
    next_node_id     VARCHAR(64),
    status           VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    user_input       TEXT,
    variables        JSONB,
    node_outputs     JSONB,
    scoped_variables JSONB,
    sys_variables    JSONB,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

COMMENT ON TABLE workflow_checkpoint IS '工作流每节点检查点：崩溃/重启后按 run_id 最新行续跑';
COMMENT ON COLUMN workflow_checkpoint.run_id IS '运行唯一标识（与 workflow_test_run / 挂起 runId 同源）';
COMMENT ON COLUMN workflow_checkpoint.step_index IS '已完成节点序号（从 1 递增）';
COMMENT ON COLUMN workflow_checkpoint.current_node_id IS '本检查点代表「已执行完成」的节点';
COMMENT ON COLUMN workflow_checkpoint.next_node_id IS '应续跑的下一节点（为空表示工作流已结束）';
COMMENT ON COLUMN workflow_checkpoint.status IS 'RUNNING | SUSPENDED | COMPLETED';

CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_run_step
    ON workflow_checkpoint (run_id, step_index DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_status_time
    ON workflow_checkpoint (status, update_time);
