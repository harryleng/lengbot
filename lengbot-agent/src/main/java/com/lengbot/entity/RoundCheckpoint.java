package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行轮次检查点（Checkpointer 风格）
 * <p>每轮（工具递归 / 流式 harness 轮次）落一份快照，支持：
 * <ul>
 *   <li>崩溃续跑：进程重启后从最近检查点恢复 messages + token 累计，避免从头重跑；</li>
 *   <li>时间旅行回放：按 run_id 拉取历史检查点，重建任意一步的状态用于调试；</li>
 *   <li>可观测：每次轮次的深度 / 阶段 / token / 中断原因一目了然。</li>
 * </ul>
 * 落盘失败不影响主流程（调用方均 try-catch）。
 *
 * @author lw
 * @since 2026-10-19
 */
@Data
@TableName("round_checkpoint")
@Schema(description = "Agent 运行轮次检查点")
public class RoundCheckpoint {

    /** 所处阶段：非流式 harness 工具递归轮次 */
    public static final String PHASE_TOOL_RECURSIVE = "TOOL_RECURSIVE";
    /** 所处阶段：legacy 阻塞式 LLM 轮次 */
    public static final String PHASE_BLOCKING_ROUND = "BLOCKING_ROUND";
    /** 所处阶段：流式 harness 轮次 */
    public static final String PHASE_STREAMING_HARNESS = "STREAMING_HARNESS";
    /** 所处阶段：子 Agent 委派 */
    public static final String PHASE_SUBAGENT = "SUBAGENT";

    /** 状态：运行中（轮次快照） */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：整轮完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 状态：已中断（断连 / 用户停止 / 预算耗尽） */
    public static final String STATUS_ABORTED = "ABORTED";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("run_id")
    @Schema(description = "运行ID（= ChatContext.requestId，单次请求唯一）")
    private String runId;

    @TableField("session_id")
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @TableField("user_id")
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @TableField("agent_id")
    @Schema(description = "AgentID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;

    @TableField("workspace_id")
    @Schema(description = "工作区ID（可空，多租户范围）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;

    @TableField("depth")
    @Schema(description = "当前工具递归 / 轮次深度")
    private Integer depth;

    @TableField("phase")
    @Schema(description = "所处阶段")
    private String phase;

    @TableField("status")
    @Schema(description = "检查点状态")
    private String status;

    @TableField("input_tokens")
    @Schema(description = "截至本检查点累计输入 Token")
    private Integer inputTokens;

    @TableField("output_tokens")
    @Schema(description = "截至本检查点累计输出 Token")
    private Integer outputTokens;

    @TableField("cached_tokens")
    @Schema(description = "截至本检查点累计缓存命中 Token")
    private Integer cachedTokens;

    @TableField("aborted")
    @Schema(description = "是否已中断")
    private Boolean aborted;

    @TableField("abort_reason")
    @Schema(description = "中断原因")
    private String abortReason;

    @TableField("state_json")
    @Schema(description = "轮次状态快照（depth/phase/tokens/abort/deadline/todos + messages 投影），JSON 文本")
    private String stateJson;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
