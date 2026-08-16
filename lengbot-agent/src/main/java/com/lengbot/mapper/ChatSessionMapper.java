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
     * 统计指定日期区间内新建的会话数（含起止日，按 create_time 过滤）
     */
    @Select("""
            SELECT COUNT(*) FROM chat_session
            WHERE create_time >= #{startDate}::date
              AND create_time < (#{endDate}::date + INTERVAL '1 day')
            """)
    long countByCreateDateRange(@Param("startDate") String startDate, @Param("endDate") String endDate);
}
