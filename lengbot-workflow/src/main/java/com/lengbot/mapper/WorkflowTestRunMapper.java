package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.WorkflowTestRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 工作流测试运行记录 Mapper
 */
@Mapper
public interface WorkflowTestRunMapper extends BaseMapper<WorkflowTestRun> {

    /**
     * 物理删除早于阈值的测试运行记录（TTL 清理）
     * <p>绕过 @TableLogic 软删除，直接 DELETE 释放空间</p>
     *
     * @param threshold 阈值时间（按 start_time 判定，早于此时间的记录将被删除）
     * @return 受影响行数
     */
    @Delete("DELETE FROM workflow_test_run WHERE start_time < #{threshold}")
    int deleteByStartTimeBefore(@Param("threshold") LocalDateTime threshold);
}
