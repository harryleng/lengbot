package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.SubAgentTaskEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** SubAgent 任务事件 Mapper。 */
@Mapper
public interface SubAgentTaskEventMapper extends BaseMapper<SubAgentTaskEvent> {

    @Select("SELECT * FROM subagent_task_event WHERE task_id = #{taskId} "
            + "AND id > #{cursor} ORDER BY id ASC LIMIT #{limit}")
    List<SubAgentTaskEvent> selectAfterCursor(@Param("taskId") String taskId,
                                              @Param("cursor") Long cursor,
                                              @Param("limit") int limit);

    @Select("SELECT * FROM subagent_task_event WHERE task_id = #{taskId} ORDER BY id ASC LIMIT #{limit}")
    List<SubAgentTaskEvent> selectFirstPage(@Param("taskId") String taskId, @Param("limit") int limit);

    @Select("SELECT * FROM subagent_task_event WHERE task_id = #{taskId} ORDER BY id DESC LIMIT 1")
    SubAgentTaskEvent selectLatestByTaskId(@Param("taskId") String taskId);
}
