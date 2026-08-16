package com.lengbot.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.vo.LlmTraceDetailVO;
import com.lengbot.dto.LlmTraceRequestDTO;
import com.lengbot.entity.LlmTrace;

import java.util.List;
import java.util.Map;

/**
 * LLM调用链追踪 Service
 *
 * @author finch
 * @since 2026-05-23
 */
public interface LlmTraceService extends IService<LlmTrace> {

    /**
     * 分页查询调用链列表
     *
     * @param request 查询参数
     * @return 分页结果
     */
    Map<String, Object> pageList(LlmTraceRequestDTO request);

    /**
     * 查询调用链详情（spans解析为对象列表）
     *
     * @param id 主键ID
     * @return Trace详情VO
     */
    LlmTraceDetailVO getDetail(Long id);

    /**
     * 汇总统计
     *
     * @param traceSource 来源类型筛选（chat/workflow/null=全部）
     * @return 统计数据
     */
    Map<String, Object> getOverview(String traceSource);

    /**
     * 异步写入调用链记录
     *
     * @param trace 调用链数据
     */
    void recordTrace(LlmTrace trace);

    /**
     * 按 requestId 查询工作流 trace
     *
     * @param requestId 请求 ID
     * @return 调用链记录，不存在时返回 null
     */
    LlmTrace findByRequestId(String requestId);

    /**
     * 查询指定请求最近一次已持久化的调用链。
     * <p>用于会话协作状态展示，包含普通对话与工作流来源。</p>
     *
     * @param requestId 请求 ID
     * @return 最近一条调用链记录，不存在时返回 null
     */
    LlmTrace findLatestByRequestId(String requestId);

    /**
     * 按 requestId 插入或更新工作流 trace（防止 resume 后异步旧快照覆盖）
     *
     * @param trace 调用链数据
     */
    void upsertWorkflowTrace(LlmTrace trace);

    /**
     * 删除会话下的所有调用链记录
     *
     * @param sessionId 会话ID
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 批量删除多个会话下的所有调用链记录（一次 IN 查询，替代 N 次 deleteBySessionId）
     *
     * @param sessionIds 会话ID集合
     */
    void deleteBySessionIds(java.util.Collection<Long> sessionIds);

    /**
     * 批量删除调用链记录（物理删除）
     *
     * @param ids 主键ID列表
     */
    void deleteByIds(List<Long> ids);

    /**
     * 按保留期清理过期调用链记录（物理删除）
     * <p>定时任务调用，避免 llm_trace 表无限增长</p>
     *
     * @param retentionDays 保留天数（早于该天数的数据将被删除）
     * @return 删除记录数
     */
    int cleanupByAge(int retentionDays);
}
