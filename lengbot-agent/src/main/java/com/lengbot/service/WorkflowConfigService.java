package com.lengbot.service;

import com.lengbot.dto.WorkflowGraphDTO;
import com.lengbot.dto.WorkflowNodeTestDTO;
import com.lengbot.dto.WorkflowAbandonDTO;
import com.lengbot.dto.WorkflowResumeDTO;
import com.lengbot.dto.WorkflowTestDTO;
import com.lengbot.vo.WorkflowTestResultVO;
import com.lengbot.vo.WorkflowTestRunDetailVO;
import com.lengbot.vo.WorkflowTestRunVO;
import com.lengbot.vo.WorkflowTestRunDetailVO;
import com.lengbot.vo.WorkflowTestRunVO;
import com.lengbot.vo.WorkflowVersionVO;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Agent 工作流配置：草稿、发布、版本、调试
 */
public interface WorkflowConfigService {

    /**
     * 获取工作流编辑态（草稿 + 发布状态 + 全局配置）
     */
    Map<String, Object> getWorkflowConfig(Long agentId);

    /**
     * 暂存草稿（跳过校验）
     */
    void saveDraft(Long agentId, WorkflowGraphDTO graph);

    /**
     * 发布工作流（必须通过校验）
     */
    Map<String, Object> publish(Long agentId, WorkflowGraphDTO graph);

    /**
     * 校验工作流配置
     */
    List<String> validate(Long agentId, WorkflowGraphDTO graph);

    /**
     * 获取已发布工作流的 IO Schema（子工作流参数映射）
     */
    Map<String, Object> getIoSchema(Long agentId);

    /**
     * 版本列表
     */
    List<WorkflowVersionVO> listVersions(Long agentId);

    /**
     * 恢复指定版本到草稿
     */
    void restoreVersion(Long agentId, Integer version);

    /**
     * 获取指定历史版本的画布配置（只读预览）
     */
    Map<String, Object> getVersionGraph(Long agentId, Integer version);

    /**
     * 调试运行
     */
    WorkflowTestResultVO testRun(Long agentId, WorkflowTestDTO request);

    /**
     * 调试运行（SSE 实时推送节点事件）
     */
    SseEmitter testRunStream(Long agentId, WorkflowTestDTO request);

    /**
     * 人工确认后恢复工作流
     */
    WorkflowTestResultVO resumeWorkflow(Long agentId, WorkflowResumeDTO request);

    /**
     * 人工确认后恢复工作流（SSE 实时推送）
     */
    SseEmitter resumeWorkflowStream(Long agentId, WorkflowResumeDTO request);

    /**
     * 放弃人工确认：删除 Redis 挂起快照并回写 Chat 消息状态
     */
    void abandonWorkflowConfirm(Long agentId, WorkflowAbandonDTO request);

    /**
     * 单节点调试运行
     */
    WorkflowTestResultVO testNode(Long agentId, WorkflowNodeTestDTO request);

    /**
     * 测试运行历史列表
     */
    List<WorkflowTestRunVO> listTestRuns(Long agentId);

    /**
     * 测试运行详情
     */
    WorkflowTestRunDetailVO getTestRun(Long agentId, String runId);

    /**
     * 删除单条测试记录
     */
    void deleteTestRun(Long agentId, String runId);

    /**
     * 清空 Agent 测试历史
     */
    void clearTestRuns(Long agentId);
}
