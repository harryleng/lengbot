package com.lengbot.service;

import com.lengbot.entity.DailyLog;
import com.lengbot.entity.ProjectMemory;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工作区记忆 + 每日日志 服务
 * <p>
 * 补齐 lengbot 缺失的「项目/工作区级记忆 + 每日工作日志」这一层（对照用户个人长期记忆
 * {@code UserMemoryService}）。注入点见 {@code MessageMiddleware.appendWorkspaceMemoryPrompt}。
 * </p>
 *
 * <h3>数据模型</h3>
 * <ul>
 *   <li>{@code project_memory}：工作区/项目级长期记忆，按 userId（可选 workspaceId）隔离，含语义向量。</li>
 *   <li>{@code daily_log}：按 userId + log_date 的每日工作记录，raw_entries 为 JSON 数组。</li>
 * </ul>
 *
 * @author lw
 * @since 2026-08-23
 */
public interface WorkspaceMemoryService {

    /**
     * 构建工作区/项目级记忆注入文本（拼进 system prompt）。
     * 命中为空时返回空串，调用方据此决定是否拼接。
     *
     * @param userId 当前用户 ID
     * @param query  当前用户消息（用于语义检索相关性；可为 null/空）
     * @return 形如 "- 偏好：...\n- 事实：..." 的条目片段；无内容返回空串
     */
    /**
     * 构建工作区记忆 prompt（注入用）。
     * @param userId 用户ID；当前记忆按 userId 全局维度存取
     * @param query 当前用户消息，用于向量语义检索
     */
    String buildWorkspaceMemoryPrompt(Long userId, String query);

    /**
     * 构建当日工作日志注入文本（拼进 system prompt）。
     * 命中为空时返回空串，调用方据此决定是否拼接。
     *
     * @param userId 当前用户 ID
     * @return 当日工作记录条目片段；无内容返回空串
     */
    /**
     * 构建每日工作日志 prompt（注入用）。
     * @param userId 用户ID；当前按 userId 全局维度存取
     */
    String buildDailyLogPrompt(Long userId);

    /**
     * 显式保存一条工作区记忆（可由 memory_save 类工具或人工调用）。
     *
     * @return 保存后的记忆 ID
     */
    /**
     * 保存工作区记忆。
     * @param workspaceId 预留字段：传 null 表示全局维度；待接入工作区隔离后启用
     */
    Long saveWorkspaceMemory(Long userId, Long workspaceId, Long sessionId, Long sourceMessageId,
                             String memoryType, String content, List<String> keywords, BigDecimal confidence);

    /**
     * 向当日工作日志追加一条记录（raw_entries 数组末尾）。
     */
    /**
     * 追加每日工作日志条目。
     * @param workspaceId 预留字段：当前传 null 表示全局维度
     */
    void appendDailyLog(Long userId, Long workspaceId, Long sessionId, String type, String content);

    /**
     * 每轮对话结束自动记录一条工作日志（轻量：记录用户本轮诉求），
     * 供 {@code ChatServiceImpl} 在抽完用户记忆后调用。
     */
    void recordTurn(Long userId, Long sessionId, Long agentId, String userMessage, String assistantReply);
}
