package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lengbot.common.BizException;
import com.lengbot.config.RedisCacheConfig;
import com.lengbot.entity.Prompt;
import com.lengbot.entity.PromptVersion;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.PromptVersionStatus;
import com.lengbot.mapper.PromptVersionMapper;
import com.lengbot.service.PromptService;
import com.lengbot.service.PromptVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 提示词版本服务实现类
 *
 * @author lw
 * @since 2026-05-27
 */
@Slf4j
@Service
public class PromptVersionServiceImpl extends ServiceImpl<PromptVersionMapper, PromptVersion>
        implements PromptVersionService {

    /** 延迟解析：PromptServiceImpl 反向依赖 PromptVersionService 构成循环，ObjectProvider 在首次调用时取 bean 打破环 */
    private final ObjectProvider<PromptService> promptServiceProvider;

    public PromptVersionServiceImpl(ObjectProvider<PromptService> promptServiceProvider) {
        this.promptServiceProvider = promptServiceProvider;
    }

    /**
     * 在版本创建流程实际需要提示词实体时再解析服务，避免构造阶段循环依赖。
     *
     * @return 提示词服务
     */
    private PromptService getPromptService() {
        return promptServiceProvider.getObject();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = RedisCacheConfig.CACHE_PROMPT_VERSION, allEntries = true)
    public PromptVersion create(String promptKey, String version, String versionDesc,
                                 String template, String variables, String modelConfig,
                                 String toolConfig, String status, Long userId) {
        // 1. 校验Prompt存在
        PromptService promptService = getPromptService();
        Prompt prompt = promptService.lambdaQuery().eq(Prompt::getPromptKey, promptKey).one();
        if (prompt == null) {
            throw new BizException(ErrorCode.PROMPT_NOT_FOUND);
        }

        // 2. 解析状态
        PromptVersionStatus versionStatus = "release".equals(status)
                ? PromptVersionStatus.RELEASE : PromptVersionStatus.PRE;

        // 3. 检查是否已存在release版本
        if (versionStatus == PromptVersionStatus.RELEASE) {
            long releaseCount = lambdaQuery()
                    .eq(PromptVersion::getPromptKey, promptKey)
                    .eq(PromptVersion::getVersion, version)
                    .eq(PromptVersion::getStatus, PromptVersionStatus.RELEASE)
                    .count();
            if (releaseCount > 0) {
                throw new BizException(ErrorCode.PROMPT_VERSION_EXISTS);
            }
        }

        // 4. 删除已有的pre版本（同promptKey同version）
        lambdaUpdate()
                .eq(PromptVersion::getPromptKey, promptKey)
                .eq(PromptVersion::getVersion, version)
                .eq(PromptVersion::getStatus, PromptVersionStatus.PRE)
                .remove();

        // 5. 创建新版本
        PromptVersion pv = new PromptVersion();
        pv.setPromptKey(promptKey);
        pv.setVersion(version);
        pv.setVersionDesc(versionDesc);
        pv.setTemplate(template);
        pv.setVariables(variables);
        pv.setModelConfig(modelConfig);
        pv.setToolConfig(toolConfig);
        pv.setStatus(versionStatus);
        pv.setUserId(userId);
        save(pv);

        // 6. 更新Prompt的latestVersion
        prompt.setLatestVersion(version);
        promptService.updateById(prompt);

        return pv;
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_PROMPT_VERSION,
               key = "#promptKey + ':' + #version",
               unless = "#result == null")
    public PromptVersion getByKeyAndVersion(String promptKey, String version) {
        return lambdaQuery()
                .eq(PromptVersion::getPromptKey, promptKey)
                .eq(PromptVersion::getVersion, version)
                .one();
    }

    @Override
    public List<PromptVersion> listByKey(String promptKey) {
        return lambdaQuery()
                .eq(PromptVersion::getPromptKey, promptKey)
                .orderByDesc(PromptVersion::getCreateTime)
                .list();
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_PROMPT_VERSION, allEntries = true)
    public void deleteByPromptKey(String promptKey) {
        remove(new LambdaQueryWrapper<PromptVersion>().eq(PromptVersion::getPromptKey, promptKey));
        log.info("[PromptVersion] 批量删除: promptKey={}", promptKey);
    }
}
