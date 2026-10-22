package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.ChatSessionSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话上下文摘要缓存 Mapper
 *
 * @author lw
 * @since 2026-10-21
 */
@Mapper
public interface ChatSessionSummaryMapper extends BaseMapper<ChatSessionSummary> {

    @Select("SELECT * FROM chat_session_summary WHERE session_id = #{sessionId}")
    ChatSessionSummary selectBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 写入或滚动覆盖该会话的摘要。
     *
     * <p>依赖 chat_session_summary(session_id) 上的唯一索引做冲突推断，
     * 一个会话恒定只保留一份最新摘要。</p>
     */
    @Update("INSERT INTO chat_session_summary " +
            "(id, session_id, upto_message_id, covered_count, covered_chars, summary, model, create_time, update_time) " +
            "VALUES (#{id}, #{sessionId}, #{uptoMessageId}, #{coveredCount}, #{coveredChars}, #{summary}, #{model}, NOW(), NOW()) " +
            "ON CONFLICT (session_id) DO UPDATE SET " +
            "upto_message_id = EXCLUDED.upto_message_id, " +
            "covered_count   = EXCLUDED.covered_count, " +
            "covered_chars   = EXCLUDED.covered_chars, " +
            "summary         = EXCLUDED.summary, " +
            "model           = EXCLUDED.model, " +
            "update_time     = NOW()")
    void upsert(ChatSessionSummary entity);

    @Update("DELETE FROM chat_session_summary WHERE session_id = #{sessionId}")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}
