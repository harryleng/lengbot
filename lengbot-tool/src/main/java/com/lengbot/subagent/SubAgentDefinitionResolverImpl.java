package com.lengbot.subagent;

import com.lengbot.entity.SubAgent;
import com.lengbot.service.SubAgentService;
import com.lengbot.subagent.spi.SubAgentDefinition;
import com.lengbot.subagent.spi.SubAgentDefinitionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 基于 Agent 绑定列表解析可委派 SubAgent。 */
@Component
@RequiredArgsConstructor
public class SubAgentDefinitionResolverImpl implements SubAgentDefinitionResolver {

    private final SubAgentService subAgentService;

    @Override
    public Map<String, SubAgentDefinition> resolve(List<Long> boundSubAgentIds) {
        if (boundSubAgentIds == null || boundSubAgentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SubAgentDefinition> definitions = new LinkedHashMap<>();
        for (SubAgent subAgent : subAgentService.listByIds(boundSubAgentIds)) {
            if (subAgent != null && Integer.valueOf(1).equals(subAgent.getEnabled())) {
                definitions.put(subAgent.getName(), new SubAgentDefinition(subAgent));
            }
        }
        return definitions;
    }
}
