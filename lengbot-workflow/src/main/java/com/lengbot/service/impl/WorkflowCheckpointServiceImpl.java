package com.lengbot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lengbot.entity.WorkflowCheckpoint;
import com.lengbot.mapper.WorkflowCheckpointMapper;
import com.lengbot.service.WorkflowCheckpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作流检查点 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCheckpointServiceImpl extends ServiceImpl<WorkflowCheckpointMapper, WorkflowCheckpoint>
        implements WorkflowCheckpointService {

    @Override
    public WorkflowCheckpoint getLatestByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        return baseMapper.selectLatestByRunId(runId);
    }

    @Override
    public void markCompleted(String runId) {
        WorkflowCheckpoint latest = getLatestByRunId(runId);
        if (latest == null) {
            return;
        }
        latest.setStatus("COMPLETED");
        updateById(latest);
    }

    @Override
    public List<String> listRecoverableRunIds(int graceSeconds) {
        return baseMapper.selectRecoverableRunIds(graceSeconds);
    }
}
