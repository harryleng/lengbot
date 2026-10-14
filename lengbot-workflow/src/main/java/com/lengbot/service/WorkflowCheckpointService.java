package com.lengbot.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.entity.WorkflowCheckpoint;

import java.util.List;

/**
 * 工作流检查点 Service
 */
public interface WorkflowCheckpointService extends IService<WorkflowCheckpoint> {

    /**
     * 取某运行的最新检查点（按 step_index 降序）
     */
    WorkflowCheckpoint getLatestByRunId(String runId);

    /**
     * 将某运行的最新检查点标记为 COMPLETED（正常结束后调用，避免被孤儿扫描误回收）
     */
    void markCompleted(String runId);

    /**
     * 列出可自愈的孤儿运行 ID（最新检查点为 RUNNING 且超过宽限期）
     *
     * @param graceSeconds 宽限秒数
     */
    List<String> listRecoverableRunIds(int graceSeconds);
}
