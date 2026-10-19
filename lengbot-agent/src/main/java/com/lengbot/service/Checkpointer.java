package com.lengbot.service;

import com.lengbot.entity.RoundCheckpoint;

import java.util.List;
import java.util.Optional;

/**
 * 轮次检查点接口（Checkpointer 风格，对齐 LangGraph 的 checkpoint 持久化语义）。
 * <p>与 LangGraph 的区别：LangGraph 在每次 node 执行后自动落盘整份 typed state；
 * 本实现在 lengbot 的 Agent 轮次（工具递归 / 阻塞轮次 / 流式 harness 轮次）边界落盘，
 * 支持崩溃续跑（resume）与时间旅行回放（time-travel）。</p>
 *
 * @author lw
 * @since 2026-10-19
 */
public interface Checkpointer {

    /**
     * 保存（或追加）一份检查点。
     *
     * @param checkpoint 检查点
     */
    void save(RoundCheckpoint checkpoint);

    /**
     * 取某运行的最新一份检查点（按创建时间倒序）。
     *
     * @param runId 运行ID
     * @return 最新检查点，无则返回 empty
     */
    Optional<RoundCheckpoint> loadLatest(String runId);

    /**
     * 列出某运行的所有检查点（按创建时间正序）。
     *
     * @param runId 运行ID
     * @return 检查点列表
     */
    List<RoundCheckpoint> listByRun(String runId);

    /**
     * 列出某会话最近 N 份检查点（跨运行，按创建时间倒序）。
     *
     * @param sessionId 会话ID
     * @param limit     数量上限
     * @return 检查点列表
     */
    List<RoundCheckpoint> listLatestBySession(Long sessionId, int limit);

    /**
     * 删除某运行的所有检查点（运行完成后清理）。
     *
     * @param runId 运行ID
     */
    void deleteByRun(String runId);
}
