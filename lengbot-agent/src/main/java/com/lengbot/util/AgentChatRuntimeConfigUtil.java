package com.lengbot.util;

import com.lengbot.constant.ConfigKeys;
import com.lengbot.entity.Agent;
import com.lengbot.enums.AgentStatus;
import com.lengbot.service.AgentVersionService;
import com.lengbot.workflow.WorkflowConfigParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 对话运行时 Agent 配置解析：与 InitMiddleware 对齐，供附件上传、能力查询等场景复用
 */
public final class AgentChatRuntimeConfigUtil {

    private static final String[] BEHAVIOR_OVERLAY_KEYS = {
            ConfigKeys.Agent.PROVIDER_ID,
            "modelId",
            "temperature",
            "topP",
            "maxTokens",
            "presencePenalty",
            "frequencyPenalty",
            ConfigKeys.Agent.USER_SENSITIVE_FILTER_ENABLED,
            ConfigKeys.Agent.USER_SENSITIVE_WORDS,
            ConfigKeys.Agent.SENSITIVE_FILTER_ENABLED,
            ConfigKeys.Agent.SENSITIVE_FILTER_STRATEGY,
            ConfigKeys.Agent.SENSITIVE_FILTER_REPLACE_TEXT,
            ConfigKeys.Agent.SENSITIVE_WORDS,
            "maxContextMessages",
            ConfigKeys.Agent.ENABLE_SUMMARY,
            ConfigKeys.Agent.SUMMARY_THRESHOLD_KB,
            ConfigKeys.Agent.SUMMARY_PROMPT,
            ConfigKeys.Agent.SUMMARY_KEEP_MESSAGES,
            ConfigKeys.Agent.SUMMARY_TOOL_RESULT_TOKEN_LIMIT,
            ConfigKeys.Agent.MAX_EXECUTION_STEPS,
            ConfigKeys.Agent.MODEL_RETRY_TIMES,
            ConfigKeys.Agent.STREAM_OUTPUT,
            ConfigKeys.Agent.MULTIMODAL_ENABLED,
            ConfigKeys.Agent.ENABLE_IMAGE_INPUT,
            ConfigKeys.Agent.ENABLE_VIDEO_INPUT,
            ConfigKeys.Agent.ENABLE_AUDIO_INPUT,
            ConfigKeys.Agent.ENABLE_FILE_READ,
            ConfigKeys.Agent.ENABLE_WEB_SEARCH,
            ConfigKeys.Agent.WEB_SEARCH_FORCE,
            ConfigKeys.Agent.WEB_SEARCH_MAX_KEYWORD,
            ConfigKeys.Agent.ENABLE_TTS,
            ConfigKeys.Agent.ENABLE_REASONING,
            ConfigKeys.Agent.ENABLE_CONTENT_SECURITY_SCAN,
    };

    private AgentChatRuntimeConfigUtil() {
    }

    /**
     * 解析 Chat 运行时 config（含版本快照 + agent 表暂存行为字段 overlay）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveForChat(Agent agent, Integer configVersion,
                                                   AgentVersionService agentVersionService,
                                                   ObjectMapper objectMapper) {
        Map<String, Object> draftConfig = WorkflowConfigParser.parseConfigMap(agent.getConfig(), objectMapper);
        Map<String, Object> runtimeConfig;

        if (configVersion != null) {
            runtimeConfig = new HashMap<>(agentVersionService.resolveRuntimeForChat(agent, configVersion));
        } else if (agent.getVersion() != null && agent.getVersion() > 0
                && (agent.getStatus() == AgentStatus.PUBLISHED || agent.getStatus() == AgentStatus.PUBLISHED_EDITING)) {
            Map<String, Object> published = agentVersionService.loadPublishedRuntimeConfig(agent.getId());
            if (published != null && published.get("config") instanceof Map<?, ?> cfgMap) {
                runtimeConfig = new HashMap<>((Map<String, Object>) cfgMap);
            } else {
                runtimeConfig = new HashMap<>(draftConfig);
            }
        } else {
            runtimeConfig = new HashMap<>(draftConfig);
        }

        overlayBehaviorConfig(runtimeConfig, draftConfig);
        return runtimeConfig;
    }

    private static void overlayBehaviorConfig(Map<String, Object> target, Map<String, Object> draft) {
        if (target == null || draft == null || draft.isEmpty()) {
            return;
        }
        for (String key : BEHAVIOR_OVERLAY_KEYS) {
            if (draft.containsKey(key)) {
                target.put(key, draft.get(key));
            }
        }
    }
}
