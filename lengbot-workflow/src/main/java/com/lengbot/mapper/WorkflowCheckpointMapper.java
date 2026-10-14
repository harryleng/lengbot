package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.WorkflowCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流检查点 Mapper
 */
@Mapper
public interface WorkflowCheckpointMapper extends BaseMapper<WorkflowCheckpoint> {

    /**
     * 取某运行的最新检查点（按 step_index 降序）
     */
    @Select("SELECT * FROM workflow_checkpoint WHERE run_id = #{runId} "
            + "ORDER BY step_index DESC LIMIT 1")
    WorkflowCheckpoint selectLatestByRunId(@Param("runId") String runId);

    /**
     * 列出可自愈的孤儿运行 ID
     * <p>规则：每个 run_id 取最新一行（step_index 最大），其状态为 RUNNING 且
     * update_time 早于 now - graceSeconds（宽限期内视为正常进行中，不误回收）。</p>
     *
     * @param graceSeconds 宽限秒数（最新检查点距现在超过该值才判定为孤儿）
     * @return 孤儿 run_id 列表
     */
    @Select("SELECT run_id FROM ("
            + "  SELECT DISTINCT ON (run_id) run_id, status, update_time "
            + "  FROM workflow_checkpoint ORDER BY run_id, step_index DESC"
            + ") latest "
            + "WHERE latest.status = 'RUNNING' "
            + "  AND latest.update_time < NOW() - (#{graceSeconds} || ' seconds')::INTERVAL")
    List<String> selectRecoverableRunIds(@Param("graceSeconds") int graceSeconds);
}
