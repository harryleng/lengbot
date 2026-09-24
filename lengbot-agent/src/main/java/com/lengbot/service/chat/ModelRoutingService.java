package com.lengbot.service.chat;

import com.lengbot.constant.ConfigKeys;
import com.lengbot.dto.ChatRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 混合模型路由：在入口根据请求复杂度，将简单请求动态切换到轻量模型（小模型），
 * 复杂请求仍使用 Agent 原配置模型（大模型）。
 *
 * <p>设计要点：
 * 1. 仅在 Agent 配置开启 modelRoutingEnabled 且配置了 lightProviderId/lightModelId 时生效；
 *    未配置则完全保持原行为（向后兼容）。
 * 2. 判定采用保守规则（宁可判为复杂）：仅当请求明确简单才切换轻量模型，避免误伤答案质量。
 * 3. 规则法零额外 LLM 调用、零延迟；后续可平滑替换为小模型分类器而不影响调用点。
 * 4. 仅作用于主 Agent 入口（InitMiddleware），子 Agent 由其自身配置决定，不受影响。
 *
 * @author lw
 * @since 2026-09-23
 */
@Slf4j
@Component
public class ModelRoutingService {

    /** 简单请求的最大字符数（超过则判为复杂）。 */
    private static final int MAX_SIMPLE_CHARS = 200;

    /** 历史消息超过该数量视为复杂（长上下文）。 */
    private static final int MAX_SIMPLE_HISTORY = 20;

    /** 复杂意图关键词：命中任一即判为复杂。 */
    private static final Pattern COMPLEX_INTENT = Pattern.compile(
            "分析|对比|比较|总结|归纳|生成|写(一?篇|份|个)|创作|代码|编程|程序|脚本|报告|方案|规划|调研|推理|翻译|解释(原理|机制|工作)|论文|设计|优化|调试|实现|架构|算法|评测|评估|计算|推导|梳理|罗列|清单|表格|思维导图|步骤|教程|指南");

    /**
     * 应用路由：根据配置与复杂度，将 configMap 与 ctx 的 providerId/modelId 切换为轻量模型（若命中简单）。
     *
     * @return 最终生效的 configMap（若原 map 不可变会被替换为新的可变 map）
     */
    public Map<String, Object> applyRouting(ChatContext ctx, Map<String, Object> configMap) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(
                configMap.getOrDefault(ConfigKeys.Agent.MODEL_ROUTING_ENABLED, false)));
        if (!enabled) {
            return configMap;
        }
        Object lightPid = configMap.get(ConfigKeys.Agent.LIGHT_PROVIDER_ID);
        Object lightMid = configMap.get(ConfigKeys.Agent.LIGHT_MODEL_ID);
        if (lightPid == null || lightMid == null
                || !StringUtils.hasText(lightPid.toString())
                || !StringUtils.hasText(lightMid.toString())) {
            log.warn("[ModelRouting] 已开启 modelRoutingEnabled 但未配置 lightProviderId/lightModelId，跳过路由");
            return configMap;
        }
        if (!isSimple(ctx)) {
            return configMap;
        }
        // 简单请求：切换为轻量模型
        Map<String, Object> effective = (configMap instanceof HashMap)
                ? configMap : new HashMap<>(configMap);
        effective.put(ConfigKeys.Agent.PROVIDER_ID, lightPid);
        effective.put(ConfigKeys.Agent.MODEL_ID, lightMid);
        if (ctx != null) {
            try {
                ctx.setProviderId(Long.parseLong(lightPid.toString().trim()));
            } catch (NumberFormatException e) {
                log.warn("[ModelRouting] lightProviderId 非合法数字: {}", lightPid);
                return configMap;
            }
        }
        log.info("[ModelRouting] 简单请求 → 轻量模型 providerId={}, modelId={}", lightPid, lightMid);
        return effective;
    }

    /**
     * 保守判定：是否简单请求（可走小模型）。任何不确定一律返回 false（判为复杂，用大模型）。
     */
    boolean isSimple(ChatContext ctx) {
        if (ctx == null) {
            return false;
        }
        ChatRequestDTO request = ctx.getRequest();
        if (request == null) {
            return false;
        }
        // 1. 有附件/文件 → 复杂（需多模态或解析）
        List<?> attachments = request.getAttachments();
        if (attachments != null && !attachments.isEmpty()) {
            return false;
        }
        // 2. 有 @提及 → 复杂（通常触发检索/工具/委派）
        List<?> mentions = request.getMentions();
        if (mentions != null && !mentions.isEmpty()) {
            return false;
        }
        // 3. 长历史上下文 → 复杂
        List<?> messages = ctx.getMessages();
        if (messages != null && messages.size() > MAX_SIMPLE_HISTORY) {
            return false;
        }
        // 4. 当前用户输入
        String text = request.getMessage();
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        // 4.1 超长 → 复杂
        if (trimmed.length() > MAX_SIMPLE_CHARS) {
            return false;
        }
        // 4.2 含代码块或多行 → 复杂
        if (trimmed.contains("```") || trimmed.contains("\n")) {
            return false;
        }
        // 4.3 含复杂意图词 → 复杂
        if (COMPLEX_INTENT.matcher(trimmed).find()) {
            return false;
        }
        // 其余视为简单（短文本、纯问答/寒暄）
        return true;
    }
}
