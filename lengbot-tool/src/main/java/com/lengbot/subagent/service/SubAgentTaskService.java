package com.lengbot.subagent.service;

import io.agentscope.core.tool.ToolCallParam;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lengbot.entity.SubAgentRun;

import java.util.List;

/** SubAgent 批次编排服务。 */
public interface SubAgentTaskService {

    /** 执行委派工具请求。 */
    String delegate(String toolInput, ToolCallParam toolContext, List<Long> boundSubAgentIds);

    /** 查询已委派任务或批次。 */
    String query(String toolInput, ToolCallParam toolContext);

    /** 请求取消已委派任务或批次。 */
    String cancel(String toolInput, ToolCallParam toolContext);

    /** 查询会话内的 SubAgent 任务列表。 */
    /**
     * 会话级运行列表；传入 parentRequestId 时收敛为当前用户消息对应的协作任务。
     */
    Page<SubAgentRun> pageRuns(Long sessionId, String batchId, String parentRequestId, int pageNum, int pageSize);

    /** 查询会话内的批次详情及其全部任务。 */
    java.util.Map<String, Object> getBatchDetail(String batchId, Long sessionId);

    /** 查询会话内的单任务详情。 */
    java.util.Map<String, Object> getTaskDetail(String taskId, Long sessionId);

    /** 取消会话内的一个批次。 */
    java.util.Map<String, Object> cancelBatch(String batchId, Long sessionId);

    /** 取消会话内的一个单任务。 */
    java.util.Map<String, Object> cancelTask(String taskId, Long sessionId);

    /** 连带取消一个父请求下所有运行中的子任务（对话停止时调用）。 */
    int cancelByParentRequestId(String requestId);

    /** 获取任务对应子线程消息快照。 */
    java.util.Map<String, Object> getTaskThreadDetail(String taskId, Long sessionId);

    /** 按事件 ID 游标获取任务运行事件。 */
    java.util.Map<String, Object> getTaskEvents(String taskId, Long sessionId, Long cursor, int limit);

    /** 获取会话侧栏所需的任务运行态摘要。 */
    /**
     * 会话级运行态摘要；parentRequestId 为空时返回会话内全部调研子智能体，
     * 非空时仅返回该轮任务的子智能体。
     */
    List<java.util.Map<String, Object>> listRuntimeSummaries(Long sessionId, String parentRequestId, int limit);

    /**
     * 回收孤儿任务（崩溃兜底）。
     *
     * <p>子 Agent 是同步嵌套在对话链路里执行的，不走 task 表的 Redis Stream 队列，
     * 因此 {@code TaskZombieScheduler} 的回收覆盖不到它。进程被强杀时，
     * 运行中的 {@code subagent_run} 没有机会推进状态，会永久停在 pending/running，
     * 连带让所属批次（{@code subagent_task_batch}）的计数永远不收敛。</p>
     *
     * <p>本方法把超时未终结的任务判为崩溃残留并推进到 failed，
     * 然后对受影响的批次调用 {@code refreshBatch} 全量重算。
     * 因为重算是幂等的全量计数（而非增量累加），顺带也能修正并发下的计数漂移。</p>
     *
     * <p><b>不做自动重试</b>：子 Agent 可能已经产生副作用（工具已执行、token 已消耗），
     * 且父对话上下文随进程一起丢失，重试结果无人接收，只会重复扣费。</p>
     *
     * @param timeoutMinutes update_time 距今超过该分钟数且仍未终结 → 判定为孤儿
     * @param batchSize      单轮最多处理条数
     * @return 本轮实际回收的任务数
     */
    int reapOrphanRuns(int timeoutMinutes, int batchSize);
}
