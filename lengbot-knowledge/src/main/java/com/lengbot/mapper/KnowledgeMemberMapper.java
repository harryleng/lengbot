package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.vo.KnowledgeMemberVO;
import com.lengbot.entity.KnowledgeMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库成员 Mapper
 *
 * @author lw
 * @since 2026-05-19
 */
@Mapper
public interface KnowledgeMemberMapper extends BaseMapper<KnowledgeMember> {

    /**
     * 连表查询知识库成员（含用户昵称、头像）
     *
     * @param knowledgeId 知识库ID
     * @return 成员VO列表
     */
    @Select("SELECT km.id, km.knowledge_id, km.user_id, km.role, km.create_time, " +
            "u.username, u.nickname, u.avatar " +
            "FROM knowledge_member km " +
            "INNER JOIN users u ON km.user_id = u.id AND u.deleted = 0 " +
            "WHERE km.knowledge_id = #{knowledgeId}")
    List<KnowledgeMemberVO> listMemberVOs(@Param("knowledgeId") Long knowledgeId);
}
