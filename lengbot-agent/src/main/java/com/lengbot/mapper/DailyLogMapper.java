package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.DailyLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 每日工作日志 Mapper
 *
 * @author lw
 * @since 2026-08-23
 */
@Mapper
public interface DailyLogMapper extends BaseMapper<DailyLog> {

    @Select("""
            SELECT id, user_id, workspace_id, log_date, summary, raw_entries, create_time, update_time, deleted
            FROM daily_log
            WHERE user_id = #{userId}
              AND log_date = #{logDate}
              AND deleted = 0
            ORDER BY create_time DESC
            LIMIT 1
            """)
    DailyLog selectByUserAndDate(@Param("userId") Long userId,
                                 @Param("logDate") java.time.LocalDate logDate);
}
