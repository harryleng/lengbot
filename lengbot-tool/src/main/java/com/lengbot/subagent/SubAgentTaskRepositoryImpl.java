package com.lengbot.subagent;

import com.lengbot.entity.SubAgentRun;
import com.lengbot.entity.SubAgentTaskBatch;
import com.lengbot.mapper.SubAgentRunMapper;
import com.lengbot.mapper.SubAgentTaskBatchMapper;
import com.lengbot.mapper.SubAgentTaskEventMapper;
import com.lengbot.entity.SubAgentTaskEvent;
import com.lengbot.subagent.spi.SubAgentTaskRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** SubAgent 任务数据访问实现。 */
@Repository
@RequiredArgsConstructor
public class SubAgentTaskRepositoryImpl implements SubAgentTaskRepository {

    private final SubAgentRunMapper subAgentRunMapper;
    private final SubAgentTaskBatchMapper subAgentTaskBatchMapper;
    private final SubAgentTaskEventMapper subAgentTaskEventMapper;

    @Override
    public SubAgentTaskBatch findBatch(String batchId) {
        return subAgentTaskBatchMapper.selectByBatchId(batchId);
    }

    @Override
    public void saveBatch(SubAgentTaskBatch batch) {
        if (batch.getId() == null) {
            subAgentTaskBatchMapper.insert(batch);
        } else {
            subAgentTaskBatchMapper.updateById(batch);
        }
    }

    @Override
    public SubAgentRun findTask(String taskId) {
        return subAgentRunMapper.selectByRequestId(taskId);
    }

    @Override
    public List<SubAgentRun> findTasks(String batchId) {
        return subAgentRunMapper.selectByBatchId(batchId);
    }

    @Override
    public void saveTask(SubAgentRun task) {
        if (task.getId() == null) {
            subAgentRunMapper.insert(task);
        } else {
            subAgentRunMapper.updateById(task);
        }
    }

    @Override
    public int requestCancelTask(String taskId) {
        return subAgentRunMapper.requestCancelByRequestId(taskId);
    }

    @Override
    public int requestCancelBatch(String batchId) {
        subAgentTaskBatchMapper.requestCancelByBatchId(batchId);
        return subAgentRunMapper.requestCancelByBatchId(batchId);
    }

    @Override
    public int requestCancelByParentRequestId(String parentRequestId) {
        return subAgentRunMapper.requestCancelByParentRequestId(parentRequestId);
    }

    @Override
    public Page<SubAgentRun> pageTasks(Long parentSessionId, String batchId, String parentRequestId,
                                       int pageNum, int pageSize) {
        return subAgentRunMapper.selectPage(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<SubAgentRun>()
                .eq(SubAgentRun::getParentSessionId, parentSessionId)
                .eq(batchId != null && !batchId.isBlank(), SubAgentRun::getBatchId, batchId)
                .eq(parentRequestId != null && !parentRequestId.isBlank(),
                        SubAgentRun::getParentRequestId, parentRequestId)
                .orderByDesc(SubAgentRun::getCreateTime));
    }

    @Override
    public void saveTaskEvent(SubAgentTaskEvent event) {
        subAgentTaskEventMapper.insert(event);
    }

    @Override
    public List<SubAgentTaskEvent> findTaskEvents(String taskId, Long cursor, int limit) {
        if (cursor == null) {
            return subAgentTaskEventMapper.selectFirstPage(taskId, limit);
        }
        return subAgentTaskEventMapper.selectAfterCursor(taskId, cursor, limit);
    }

    @Override
    public SubAgentTaskEvent findLatestTaskEvent(String taskId) {
        return subAgentTaskEventMapper.selectLatestByTaskId(taskId);
    }
}
