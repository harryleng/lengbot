package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.SubAgentTaskBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * SubAgentTaskBatch Mapper。
 *
 * @author finch
 * @since 2026-07-09
 */
@Mapper
public interface SubAgentTaskBatchMapper extends BaseMapper<SubAgentTaskBatch> {

    @Select("SELECT * FROM subagent_task_batch WHERE batch_id = #{batchId} LIMIT 1")
    SubAgentTaskBatch selectByBatchId(@Param("batchId") String batchId);

    @Update("""
            UPDATE subagent_task_batch
            SET cancel_requested = 1,
                status = CASE WHEN status = 'pending' THEN 'cancelled' ELSE status END,
                update_time = CURRENT_TIMESTAMP
            WHERE batch_id = #{batchId}
            """)
    int requestCancelByBatchId(@Param("batchId") String batchId);
}
