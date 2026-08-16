package com.lengbot.subagent.spi;

import com.lengbot.entity.SubAgentRun;
import com.lengbot.entity.SubAgentTaskBatch;
import com.lengbot.entity.SubAgentTaskEvent;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/** SubAgent 批次和任务持久化 SPI。 */
public interface SubAgentTaskRepository {

    SubAgentTaskBatch findBatch(String batchId);

    void saveBatch(SubAgentTaskBatch batch);

    SubAgentRun findTask(String taskId);

    List<SubAgentRun> findTasks(String batchId);

    void saveTask(SubAgentRun task);

    int requestCancelTask(String taskId);

    int requestCancelBatch(String batchId);

    /** 请求取消一个父请求下所有运行中的任务（对话停止时连带取消子任务）。 */
    int requestCancelByParentRequestId(String parentRequestId);

    /** 分页查询一个会话下的任务运行记录。 */
    /**
     * 查询会话内的运行任务。
     *
     * <p>{@code parentRequestId} 对应一次用户消息触发的主 Agent 请求；传入时用于
     * 协作面板，仅返回该次任务产生的全部委派批次。未传入时保留会话级查询语义，
     * 供“子智能体状态”侧栏查看本会话的全部调研任务。</p>
     */
    Page<SubAgentRun> pageTasks(Long parentSessionId, String batchId, String parentRequestId,
                                int pageNum, int pageSize);

    void saveTaskEvent(SubAgentTaskEvent event);

    List<SubAgentTaskEvent> findTaskEvents(String taskId, Long cursor, int limit);

    SubAgentTaskEvent findLatestTaskEvent(String taskId);
}
