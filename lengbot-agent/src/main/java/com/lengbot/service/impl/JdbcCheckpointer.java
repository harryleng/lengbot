package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lengbot.entity.RoundCheckpoint;
import com.lengbot.mapper.RoundCheckpointMapper;
import com.lengbot.service.Checkpointer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus / PostgreSQL 的检查点实现。
 * <p>落盘异常全部捕获并降级（warn 日志），保证检查点机制永远不阻塞主对话流程。</p>
 *
 * @author lw
 * @since 2026-10-19
 */
@Slf4j
@Service
public class JdbcCheckpointer implements Checkpointer {

    private final RoundCheckpointMapper mapper;

    public JdbcCheckpointer(RoundCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(RoundCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        try {
            if (checkpoint.getCreatedAt() == null) {
                checkpoint.setCreatedAt(LocalDateTime.now());
            }
            mapper.insert(checkpoint);
        } catch (Exception e) {
            log.warn("[Checkpointer] 保存检查点失败(runId={}, phase={}): {}", checkpoint.getRunId(), checkpoint.getPhase(), e.getMessage());
        }
    }

    @Override
    public Optional<RoundCheckpoint> loadLatest(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        try {
            LambdaQueryWrapper<RoundCheckpoint> w = new LambdaQueryWrapper<RoundCheckpoint>()
                    .eq(RoundCheckpoint::getRunId, runId)
                    .orderByDesc(RoundCheckpoint::getCreatedAt)
                    .last("LIMIT 1");
            return Optional.ofNullable(mapper.selectOne(w));
        } catch (Exception e) {
            log.warn("[Checkpointer] 加载最新检查点失败(runId={}): {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<RoundCheckpoint> listByRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            LambdaQueryWrapper<RoundCheckpoint> w = new LambdaQueryWrapper<RoundCheckpoint>()
                    .eq(RoundCheckpoint::getRunId, runId)
                    .orderByAsc(RoundCheckpoint::getCreatedAt);
            return mapper.selectList(w);
        } catch (Exception e) {
            log.warn("[Checkpointer] 列举检查点失败(runId={}): {}", runId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<RoundCheckpoint> listLatestBySession(Long sessionId, int limit) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        try {
            LambdaQueryWrapper<RoundCheckpoint> w = new LambdaQueryWrapper<RoundCheckpoint>()
                    .eq(RoundCheckpoint::getSessionId, sessionId)
                    .orderByDesc(RoundCheckpoint::getCreatedAt)
                    .last("LIMIT " + Math.max(1, limit));
            return mapper.selectList(w);
        } catch (Exception e) {
            log.warn("[Checkpointer] 列举会话检查点失败(sessionId={}): {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteByRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            LambdaQueryWrapper<RoundCheckpoint> w = new LambdaQueryWrapper<RoundCheckpoint>()
                    .eq(RoundCheckpoint::getRunId, runId);
            mapper.delete(w);
        } catch (Exception e) {
            log.warn("[Checkpointer] 删除检查点失败(runId={}): {}", runId, e.getMessage());
        }
    }
}
