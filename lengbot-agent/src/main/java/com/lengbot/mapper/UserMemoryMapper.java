package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户长期记忆 Mapper
 *
 * @author finch
 * @since 2026-07-09
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

    /**
     * 更新记忆向量
     *
     * @param id     记忆ID
     * @param vector 向量字符串
     */
    @Update("UPDATE user_memory SET embedding_vector = #{vector}::vector, update_time = NOW() WHERE id = #{id}")
    void updateEmbeddingVector(@Param("id") Long id, @Param("vector") String vector);

    /**
     * 语义检索用户长期记忆
     *
     * @param userId  用户ID
     * @param agentId AgentID
     * @param vector  查询向量
     * @param limit   返回数量
     * @return 记忆列表
     */
    @Select("""
            SELECT id, user_id, agent_id, session_id, memory_type, content, keywords,
                   source_message_id, confidence, status, last_used_at, create_time, update_time, deleted
            FROM user_memory
            WHERE user_id = #{userId}
              AND deleted = 0
              AND status = 'active'
              AND embedding_vector IS NOT NULL
              AND (agent_id IS NULL OR agent_id = #{agentId})
            ORDER BY embedding_vector <=> #{vector}::vector
            LIMIT #{limit}
            """)
    List<UserMemory> searchSemantic(@Param("userId") Long userId,
                                    @Param("agentId") Long agentId,
                                    @Param("vector") String vector,
                                    @Param("limit") int limit);
}
