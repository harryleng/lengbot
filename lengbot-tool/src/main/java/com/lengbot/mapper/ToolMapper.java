package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.Tool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ToolMapper extends BaseMapper<Tool> {

    @Select("SELECT * FROM tool WHERE name = #{name} AND deleted = 0")
    Tool selectByName(String name);
}
