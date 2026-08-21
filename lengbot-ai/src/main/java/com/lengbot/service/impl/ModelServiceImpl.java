package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lengbot.common.BizException;
import com.lengbot.dto.ModelRequestDTO;
import com.lengbot.entity.Model;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.ModelType;
import com.lengbot.mapper.ModelMapper;
import com.lengbot.service.ModelService;
import com.lengbot.util.ModelCacheUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型服务实现类
 *
 * @author lw
 * @since 2026-05-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl extends ServiceImpl<ModelMapper, Model>
        implements ModelService {

    private final ModelCacheUtil modelCacheUtil;

    @Override
    public Model create(ModelRequestDTO request) {
        // 1. 校验同一提供商下模型标识不重复
        long count = count(new LambdaQueryWrapper<Model>()
                .eq(Model::getProviderId, request.getProviderId())
                .eq(Model::getModelId, request.getModelId()));
        if (count > 0) {
            throw new BizException(ErrorCode.MODEL_ALREADY_EXISTS);
        }
        // 2. 构建实体并保存
        Model model = new Model();
        model.setProviderId(request.getProviderId());
        model.setModelId(request.getModelId());
        model.setName(request.getName());
        model.setType(request.getType());
        model.setStatus(CommonStatus.ACTIVE);
        save(model);
        syncCache(model.getProviderId());
        return model;
    }

    @Override
    public List<Model> listByProviderId(Long providerId) {
        return list(new LambdaQueryWrapper<Model>()
                .eq(Model::getProviderId, providerId)
                .orderByAsc(Model::getType)
                .orderByAsc(Model::getModelId));
    }

    @Override
    public List<Model> listByType(ModelType type) {
        return list(new LambdaQueryWrapper<Model>()
                .eq(Model::getType, type)
                .eq(Model::getStatus, CommonStatus.ACTIVE)
                .orderByAsc(Model::getProviderId)
                .orderByAsc(Model::getModelId));
    }

    @Override
    public void deleteById(Long id) {
        Model model = getById(id);
        if (!removeById(id)) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
        if (model != null) {
            syncCache(model.getProviderId());
        }
    }

    /**
     * 模型变更后增量同步缓存（仅刷新受影响的 providerId）
     */
    private void syncCache(Long providerId) {
        List<Model> models = list(new LambdaQueryWrapper<Model>()
                .eq(Model::getProviderId, providerId));
        modelCacheUtil.cacheModelsByProviderId(providerId, models);
    }

    @Override
    public void deleteByProviderId(Long providerId) {
        remove(new LambdaQueryWrapper<Model>().eq(Model::getProviderId, providerId));
        syncCache(providerId);
        log.info("[Model] 批量删除: providerId={}", providerId);
    }
}
