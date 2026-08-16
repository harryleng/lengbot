package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.LlmTrace;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM调用链追踪 Mapper
 *
 * @author finch
 * @since 2026-05-23
 */
@Mapper
public interface LlmTraceMapper extends BaseMapper<LlmTrace> {

    /**
     * SQL 聚合统计（替代全量加载到内存的 Java Stream 聚合）
     *
     * @param traceSource 来源类型（chat/workflow/null 表示全部）
     * @param startTime   起始时间
     * @return 聚合结果（total_count, total_tokens, avg_duration_ms, total_tool_calls）
     */
    @Select("""
            SELECT
                COUNT(*) AS total_count,
                COALESCE(SUM(total_tokens), 0) AS total_tokens,
                COALESCE(AVG(total_duration_ms), 0) AS avg_duration_ms,
                COALESCE(SUM(tool_call_count), 0) AS total_tool_calls
            FROM llm_trace
            WHERE (#{traceSource} IS NULL AND (trace_source = 'chat' OR trace_source = 'workflow' OR trace_source IS NULL))
               OR (#{traceSource} IS NOT NULL AND trace_source = #{traceSource})
            """)
    Map<String, Object> aggregateOverview(@Param("traceSource") String traceSource);

    /**
     * 物理删除早于阈值的调用链记录（TTL 清理）
     * <p>llm_trace 表无 deleted 列、不走 @TableLogic，直接 DELETE 释放空间</p>
     *
     * @param threshold 阈值时间（早于此时间的记录将被删除）
     * @return 受影响行数
     */
    @Delete("DELETE FROM llm_trace WHERE create_time < #{threshold}")
    int deleteByCreateTimeBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * 按用户聚合指定时间段内的 token 总消耗（管理员排行榜：近 7 天 / 近 2 周 / 近 1 个月）
     * <p>别名用 snake_case：PG 会把未加引号的别名折叠为小写，Java 端按 snake_case key 取值，
     * 与 {@link #aggregateOverview} 风格一致</p>
     *
     * @param begin 起始时间（含）
     * @param end   结束时间（不含）
     * @param limit 返回条数
     * @return 每项 {user_id, total_tokens}，按 total_tokens 降序
     */
    @Select("""
            SELECT user_id, COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM llm_trace
            WHERE create_time >= #{begin} AND create_time < #{end}
              AND total_tokens > 0
              AND user_id IS NOT NULL
            GROUP BY user_id
            ORDER BY total_tokens DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> aggregateUserTokens(@Param("begin") LocalDateTime begin,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("limit") int limit);

    /**
     * 单用户在指定时间段内的 token 总消耗（个人页"近 7 天累计"展示）
     *
     * @param userId 用户ID
     * @param begin  起始时间（含）
     * @param end    结束时间（不含）
     * @return token 总数（无数据返回 0）
     */
    @Select("""
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM llm_trace
            WHERE user_id = #{userId}
              AND create_time >= #{begin}
              AND create_time < #{end}
              AND total_tokens > 0
            """)
    long sumUserTokens(@Param("userId") Long userId,
                       @Param("begin") LocalDateTime begin,
                       @Param("end") LocalDateTime end);
}
