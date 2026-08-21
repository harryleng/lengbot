package com.lengbot.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.vo.ModelProviderPresetVO;
import com.lengbot.dto.ModelProviderDTO;
import com.lengbot.entity.ModelProvider;

import java.util.List;

/**
 * 模型提供商服务接口
 *
 * @author lw
 * @since 2026-05-19
 */
public interface ModelProviderService extends IService<ModelProvider> {

    /**
     * 创建模型提供商
     *
     * @param request 创建请求
     * @return 模型提供商
     */
    ModelProvider create(ModelProviderDTO request);

    /**
     * 更新模型提供商
     *
     * @param request 更新请求
     * @return 模型提供商
     */
    ModelProvider update(ModelProviderDTO request);

    /**
     * 分页查询
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    Page<ModelProvider> listPage(int pageNum, int pageSize);

    /**
     * 删除模型提供商
     *
     * @param id 主键ID
     */
    void deleteById(Long id);

    /**
     * 切换提供商状态
     *
     * @param id     主键ID
     * @param status 目标状态（active / disabled）
     */
    void updateStatus(Long id, String status);

    /**
     * 查询所有启用的提供商
     *
     * @return 启用的提供商列表
     */
    List<ModelProvider> listAllActive();

    /**
     * 查询模型提供商预设列表
     *
     * @return 预设列表
     */
    List<ModelProviderPresetVO> listPresets();

    /**
     * 拉取所有启用提供商及其模型（按模型类型可选过滤）
     * <p>Controller 入口的聚合查询，编排逻辑下沉到 Service：
     * 取消「Controller 内 for 循环 join 构建 VO」的违例</p>
     *
     * @param modelType 模型类型 code（chat / embedding / rerank / vision / voice），为空则全部返回
     * @return 提供商及其模型 VO 列表，过滤后无模型的提供商不返回
     */
    List<ProviderWithModelsVO> listWithModels(String modelType);

    /**
     * 提供商及其模型列表 VO
     *
     * @param id      提供商 ID
     * @param name    提供商名称
     * @param type    提供商类型
     * @param models  模型列表
     */
    record ProviderWithModelsVO(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            com.lengbot.enums.ModelProviderType type,
            List<com.lengbot.entity.Model> models
    ) {}
}
