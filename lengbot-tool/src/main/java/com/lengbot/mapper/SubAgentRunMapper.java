package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.SubAgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * SubAgentRun Mapper
 *
 * @author finch
 * @since 2026-06-25
 */
@Mapper
public interface SubAgentRunMapper extends BaseMapper<SubAgentRun> {

    @Select("SELECT * FROM subagent_run WHERE request_id = #{requestId} ORDER BY create_time DESC LIMIT 1")
    SubAgentRun selectByRequestId(String requestId);

    @Select("SELECT * FROM subagent_run WHERE batch_id = #{batchId} ORDER BY create_time ASC")
    List<SubAgentRun> selectByBatchId(String batchId);

    @Update("""
            UPDATE subagent_run
            SET cancel_requested = 1,
                status = CASE WHEN status = 'pending' THEN 'cancelled' ELSE status END,
                update_time = CURRENT_TIMESTAMP
            WHERE request_id = #{requestId}
            """)
    int requestCancelByRequestId(@Param("requestId") String requestId);

    @Update("""
            UPDATE subagent_run
            SET cancel_requested = 1,
                status = CASE WHEN status = 'pending' THEN 'cancelled' ELSE status END,
                update_time = CURRENT_TIMESTAMP
            WHERE batch_id = #{batchId}
            """)
    int requestCancelByBatchId(@Param("batchId") String batchId);

    @Update("""
            UPDATE subagent_run
            SET cancel_requested = 1,
                status = CASE WHEN status = 'pending' THEN 'cancelled' ELSE status END,
                update_time = CURRENT_TIMESTAMP
            WHERE parent_request_id = #{parentRequestId}
              AND status IN ('pending', 'running')
            """)
    int requestCancelByParentRequestId(@Param("parentRequestId") String parentRequestId);
}
