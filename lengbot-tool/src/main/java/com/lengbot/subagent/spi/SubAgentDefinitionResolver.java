package com.lengbot.subagent.spi;

import java.util.List;
import java.util.Map;

/** SubAgent 定义解析 SPI。 */
public interface SubAgentDefinitionResolver {

    /**
     * 解析当前主 Agent 可以委派的 SubAgent。
     *
     * @param boundSubAgentIds 主 Agent 绑定的 SubAgent ID
     * @return name 到定义的映射
     */
    Map<String, SubAgentDefinition> resolve(List<Long> boundSubAgentIds);
}
