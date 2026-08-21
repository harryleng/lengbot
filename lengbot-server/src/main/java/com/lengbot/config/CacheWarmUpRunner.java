package com.lengbot.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lengbot.config.RedisCacheConfig;
import com.lengbot.entity.Agent;
import com.lengbot.entity.EvalDataset;
import com.lengbot.entity.EvalEvaluator;
import com.lengbot.entity.EvalExperiment;
import com.lengbot.entity.Knowledge;
import com.lengbot.entity.McpServer;
import com.lengbot.entity.Model;
import com.lengbot.entity.ModelProvider;
import com.lengbot.entity.Skill;
import com.lengbot.entity.SubAgent;
import com.lengbot.entity.SystemConfig;
import com.lengbot.entity.Tool;
import com.lengbot.service.AgentService;
import com.lengbot.service.EvalDatasetService;
import com.lengbot.service.EvalEvaluatorService;
import com.lengbot.service.EvalExperimentService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.service.McpServerService;
import com.lengbot.service.ModelProviderService;
import com.lengbot.service.ModelService;
import com.lengbot.service.SkillService;
import com.lengbot.service.SubAgentService;
import com.lengbot.service.SystemConfigService;
import com.lengbot.service.ToolService;
import com.lengbot.util.BloomFilterHelper;
import com.lengbot.util.ModelCacheUtil;
import com.lengbot.util.ModelProviderCacheUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 启动时预热模型和提供商缓存到Redis
 * <p>先检查Redis是否已有数据，避免重复加载</p>
 *
 * @author lw
 * @since 2026-05-22
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class CacheWarmUpRunner implements ApplicationRunner {

    private final StringRedisTemplate stringRedisTemplate;
    private final ModelProviderService modelProviderService;
    private final ModelService modelService;
    private final ModelProviderCacheUtil providerCacheUtil;
    private final ModelCacheUtil modelCacheUtil;
    private final CacheManager cacheManager;
    private final AgentService agentService;
    private final KnowledgeService knowledgeService;
    private final ToolService toolService;
    private final McpServerService mcpServerService;
    private final SubAgentService subAgentService;
    private final SkillService skillService;
    private final SystemConfigService systemConfigService;
    private final EvalDatasetService evalDatasetService;
    private final EvalEvaluatorService evalEvaluatorService;
    private final EvalExperimentService evalExperimentService;
    private final BloomFilterHelper bloomFilterHelper;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 预热模型提供商缓存（旧逻辑）
        warmUpProviders();
        // 2. 预热模型缓存（旧逻辑）
        warmUpModels();
        // 3. 预热 Spring Cache 管理的业务缓存
        warmUpSpringCaches();
        // 4. 初始化布隆过滤器（防穿透）
        initBloomFilters();
        log.info("[CacheWarmUp] 缓存预热完成");
    }

    private void warmUpProviders() {
        try {
            String existing = stringRedisTemplate.opsForValue().get("lengbot:model_provider:all");
            if (existing != null && !existing.isEmpty()) {
                log.info("[CacheWarmUp] Redis已有提供商缓存，跳过预热");
                return;
            }
            // deleted=0 由 @TableLogic 在 Service 层自动过滤
            List<ModelProvider> providers = modelProviderService.list(
                    new LambdaQueryWrapper<ModelProvider>().orderByDesc(ModelProvider::getCreateTime));
            providerCacheUtil.cacheAllProviders(providers);
            log.info("[CacheWarmUp] 提供商缓存预热完成: count={}", providers.size());
        } catch (Exception e) {
            log.warn("[CacheWarmUp] 提供商缓存预热失败: {}", e.getMessage());
        }
    }

    private void warmUpModels() {
        try {
            String existing = stringRedisTemplate.opsForValue().get("lengbot:model:all");
            if (existing != null && !existing.isEmpty()) {
                log.info("[CacheWarmUp] Redis已有模型缓存，跳过预热");
                return;
            }
            List<Model> models = modelService.list(
                    new LambdaQueryWrapper<Model>().orderByAsc(Model::getProviderId));
            modelCacheUtil.cacheAllModels(models);
            log.info("[CacheWarmUp] 模型缓存预热完成: count={}", models.size());
        } catch (Exception e) {
            log.warn("[CacheWarmUp] 模型缓存预热失败: {}", e.getMessage());
        }
    }

    /**
     * 预热 Spring Cache 管理的业务缓存（Agent/Knowledge/Tool/McpServer/SubAgent/Skill/SystemConfig/Eval*）
     * <p>逻辑删除过滤由 @TableLogic 在 Service 层自动应用，调用方无需显式 .eq(deleted, 0)</p>
     */
    private void warmUpSpringCaches() {
        log.info("[CacheWarmUp] 开始预热业务缓存...");
        warmUpCache(RedisCacheConfig.CACHE_AGENT, agentService::list, Agent::getId);
        warmUpCache(RedisCacheConfig.CACHE_KNOWLEDGE, knowledgeService::list, Knowledge::getId);
        warmUpCache(RedisCacheConfig.CACHE_TOOL, toolService::list, Tool::getId);
        warmUpCache(RedisCacheConfig.CACHE_MCP_SERVER, mcpServerService::list, McpServer::getId);
        warmUpCache(RedisCacheConfig.CACHE_SUBAGENT, subAgentService::list, SubAgent::getId);
        warmUpCache(RedisCacheConfig.CACHE_SKILL, skillService::list, Skill::getId);
        // SystemConfig 无 @TableLogic，全量加载
        warmUpCache(RedisCacheConfig.CACHE_SYSTEM_CONFIG, systemConfigService::list, SystemConfig::getConfigKey);
        warmUpCache(RedisCacheConfig.CACHE_EVAL_DATASET, evalDatasetService::list, EvalDataset::getId);
        warmUpCache(RedisCacheConfig.CACHE_EVAL_EVALUATOR, evalEvaluatorService::list, EvalEvaluator::getId);
        warmUpCache(RedisCacheConfig.CACHE_EVAL_EXPERIMENT, evalExperimentService::list, EvalExperiment::getId);
    }

    /**
     * 初始化布隆过滤器，加载各业务域的实体 ID
     */
    private void initBloomFilters() {
        try {
            bloomFilterHelper.init(RedisCacheConfig.CACHE_AGENT,
                    agentService.list().stream().map(Agent::getId).toList());
            bloomFilterHelper.init(RedisCacheConfig.CACHE_KNOWLEDGE,
                    knowledgeService.list().stream().map(Knowledge::getId).toList());
            bloomFilterHelper.init(RedisCacheConfig.CACHE_TOOL,
                    toolService.list().stream().map(Tool::getId).toList());
            log.info("[CacheWarmUp] 布隆过滤器初始化完成");
        } catch (Exception e) {
            log.warn("[CacheWarmUp] 布隆过滤器初始化失败: {}", e.getMessage());
        }
    }

    private <T> void warmUpCache(String cacheName,
                                  java.util.function.Supplier<List<T>> supplier,
                                  Function<T, Object> keyExtractor) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) return;
            List<T> data = supplier.get();
            for (T item : data) {
                cache.put(keyExtractor.apply(item), item);
            }
            log.info("[CacheWarmUp] {}缓存预热完成: count={}", cacheName, data.size());
        } catch (Exception e) {
            log.warn("[CacheWarmUp] {}缓存预热失败: {}", cacheName, e.getMessage());
        }
    }
}
