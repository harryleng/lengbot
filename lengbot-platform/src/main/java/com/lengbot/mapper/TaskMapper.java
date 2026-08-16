package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务队列 Mapper
 *
 * @author finch
 * @since 2026-05-21
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
