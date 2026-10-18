package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Update("UPDATE chat_session SET message_count = message_count + #{msgDelta}, " +
            "total_tokens = total_tokens + #{tokenDelta}, last_message_at = NOW() WHERE id = #{sessionId}")
    void incrementStats(@Param("sessionId") Long sessionId, @Param("msgDelta") int msgDelta, @Param("tokenDelta") long tokenDelta);

    /**
     * 原子递减统计（重新生成删除助手消息时使用）。message_count 用 GREATEST 兜底防负数。
     */
    @Update("UPDATE chat_session SET message_count = GREATEST(message_count + #{msgDelta}, 0), " +
            "total_tokens = GREATEST(total_tokens + #{tokenDelta}, 0) WHERE id = #{sessionId}")
    void decrementStats(@Param("sessionId") Long sessionId, @Param("msgDelta") int msgDelta, @Param("tokenDelta") long tokenDelta);

    /**
     * 仅取 user_id（主键点查，不走实体缓存）。用于统计累加后失效列表缓存，避免为了一个外键
     * 反序列化整份会话详情。
     */
    @Select("SELECT user_id FROM chat_session WHERE id = #{sessionId} AND deleted = 0")
    Long selectUserIdById(@Param("sessionId") Long sessionId);

    /**
     * 统计指定日期区间内新建的会话数（含起止日，按 create_time 过滤）
     */
    @Select("""
            SELECT COUNT(*) FROM chat_session
            WHERE create_time >= #{startDate}::date
              AND create_time < (#{endDate}::date + INTERVAL '1 day')
            """)
    long countByCreateDateRange(@Param("startDate") String startDate, @Param("endDate") String endDate);
}
