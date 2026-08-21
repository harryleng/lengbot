package com.lengbot.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.dto.SubAgentRequestDTO;
import com.lengbot.entity.SubAgent;

import java.util.List;

/**
 * SubAgent 服务接口
 *
 * @author lw
 * @since 2026-05-24
 */
public interface SubAgentService extends IService<SubAgent> {

    /**
     * 创建 SubAgent
     */
    SubAgent create(SubAgentRequestDTO request);

    /**
     * 更新 SubAgent
     */
    SubAgent update(SubAgentRequestDTO request);

    /**
     * 分页查询 SubAgent
     */
    Page<SubAgent> listPage(int pageNum, int pageSize, String keyword, Boolean isBuiltin);

    /**
     * 根据 name 获取 SubAgent
     */
    SubAgent getByName(String name);

    /**
     * 按一组 name 批量查询 SubAgent（一次 IN 查询，替代 N 次 getByName）
     *
     * @param names 名称集合
     * @return 命中的 SubAgent 列表（不含未命中项）
     */
    List<SubAgent> listByNameIn(java.util.Collection<String> names);

    /**
     * 获取所有启用的 SubAgent
     */
    List<SubAgent> listEnabled();

    /**
     * 删除 SubAgent（内置不可删）
     */
    void deleteById(Long id);

    /**
     * 设置启用状态
     */
    void setEnabled(Long id, boolean enabled);
}