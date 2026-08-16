package com.lengbot.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.dto.WorkflowTestDTO;
import com.lengbot.vo.WorkflowTestResultVO;
import com.lengbot.vo.WorkflowTestRunDetailVO;
import com.lengbot.vo.WorkflowTestRunVO;
import com.lengbot.entity.WorkflowTestRun;
import com.lengbot.workflow.WorkflowDefinition;

import java.util.List;

/**
 * 工作流编排页测试运行记录
 */
public interface WorkflowTestRunService extends IService<WorkflowTestRun> {

    /**
     * 创建 running 状态记录
     *
     * @return runId
     */
    String startRun(Long agentId, Long userId, WorkflowTestDTO request,
                    WorkflowDefinition definition, boolean usedDraft);

    /**
     * 测试执行完成后更新记录
     */
    void finishRun(String runId, WorkflowTestResultVO result, long durationMs, String errorInfo);

    /**
     * resume 完成后更新同 runId 记录（仅测试来源记录存在时）
     */
    void updateAfterResume(String runId, WorkflowTestResultVO result, long durationMs, String errorInfo);

    /**
     * 列表（最近 N 条）
     */
    List<WorkflowTestRunVO> listByAgent(Long agentId, int limit);

    /**
     * 详情
     */
    WorkflowTestRunDetailVO getDetail(Long agentId, String runId);

    /**
     * 删除单条
     */
    void deleteRun(Long agentId, String runId);

    /**
     * 清空 Agent 测试历史
     */
    void clearByAgent(Long agentId);

    /**
     * 按 runId 查 DB 主键
     */
    Long findIdByRunId(String runId);

    /**
     * 按保留期清理过期测试运行记录（物理删除）
     * <p>定时任务调用，避免 workflow_test_run 表无限增长</p>
     *
     * @param retentionDays 保留天数（早于该天数的数据将被删除）
     * @return 删除记录数
     */
    int cleanupByAge(int retentionDays);
}
