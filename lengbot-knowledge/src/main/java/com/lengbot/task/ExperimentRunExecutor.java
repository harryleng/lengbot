package com.lengbot.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.task.TaskCancelledException;
import com.lengbot.entity.Task;
import com.lengbot.service.EvalExperimentService;
import com.lengbot.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 实验执行任务执行器
 *
 * @author finch
 * @since 2026-05-27
 */
@Slf4j
@Component("experimentRunExecutor")
@RequiredArgsConstructor
public class ExperimentRunExecutor implements TaskExecutor {

    private final EvalExperimentService evalExperimentService;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Override
    public String execute(Task task) throws Exception {
        JsonNode payload = objectMapper.readTree(task.getPayload());
        Long experimentId = payload.get("experimentId").asLong();
        log.info("[实验执行器] 开始, taskId={}, experimentId={}", task.getId(), experimentId);
        checkCancelled(task.getId());
        evalExperimentService.executeExperiment(experimentId, task);
        return "实验执行完成, experimentId=" + experimentId;
    }

    private void checkCancelled(Long taskId) {
        if (redisUtil.hasCancelSignal(taskId)) {
            throw new TaskCancelledException();
        }
    }
}
