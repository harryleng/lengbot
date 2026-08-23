package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.ProjectMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工作区/项目级长期记忆 Mapper
 *
 * @author lw
 * @since 2026-08-23
 */
@Mapper
public interface ProjectMemoryMapper extends BaseMapper<ProjectMemory> {

    @Update("UPDATE project_memory SET embedding_vector = #{vector}::vector, update_time = NOW() WHERE id = #{id}")
    void updateEmbeddingVector(@Param("id") Long id, @Param("vector") String vector);

    @Select("""
            SELECT id, user_id, workspace_id, session_id, memory_type, content, keywords,
                   source_message_id, confidence, status, last_used_at, create_time, update_time, deleted
            FROM project_memory
            WHERE user_id = #{userId}
              AND deleted = 0
              AND status = 'active'
              AND embedding_vector IS NOT NULL
              AND (workspace_id IS NULL OR workspace_id = #{workspaceId})
            ORDER BY embedding_vector <=> #{vector}::vector
            LIMIT #{limit}
            """)
    List<ProjectMemory> searchSemantic(@Param("userId") Long userId,
                                       @Param("workspaceId") Long workspaceId,
                                       @Param("vector") String vector,
                                       @Param("limit") int limit);
}
