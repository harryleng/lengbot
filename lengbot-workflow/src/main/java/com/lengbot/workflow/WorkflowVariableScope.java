package com.lengbot.workflow;

import com.lengbot.enums.NodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流命名空间变量池：scopedVariables + sysVariables 双写，兼容扁平 variables
 */
public final class WorkflowVariableScope {

    private static final List<String> SYS_KEYS = List.of(
            "query", "input", "history_list", "history", "session_id", "agent_id");

    private WorkflowVariableScope() {
    }

    /**
     * 初始化上下文变量 Map（扁平 + 命名空间 + 系统桶）
     */
    public static void initContext(NodeExecutionContext context) {
        if (context.getVariables() == null) {
            context.setVariables(new LinkedHashMap<>());
        }
        if (context.getNodeOutputs() == null) {
            context.setNodeOutputs(new LinkedHashMap<>());
        }
        if (context.getScopedVariables() == null) {
            context.setScopedVariables(new LinkedHashMap<>());
        }
        if (context.getSysVariables() == null) {
            context.setSysVariables(new LinkedHashMap<>());
        }
    }

    /**
     * 将扁平 variables 中的系统变量同步到 sys 桶（兼容 {{sys.query}} 与 {{query}}）
     */
    public static void syncSysBucket(NodeExecutionContext context) {
        if (context == null || context.getVariables() == null) {
            return;
        }
        initContext(context);
        Map<String, Object> vars = context.getVariables();
        Map<String, Object> sys = context.getSysVariables();
        for (String key : SYS_KEYS) {
            if (vars.containsKey(key)) {
                sys.put(key, vars.get(key));
            }
        }
        context.getScopedVariables().put("sys", new LinkedHashMap<>(sys));
    }

    /**
     * 按 nodeId 写入命名空间变量，并同步兼容扁平层
     */
    public static void mergeNodeOutputs(NodeExecutionContext context, WorkflowNode node,
                                        Map<String, Object> rawOutputs) {
        if (context == null || node == null || rawOutputs == null || rawOutputs.isEmpty()) {
            return;
        }
        initContext(context);
        NodeType type = node.getType();
        Map<String, Object> filtered = NodeIoContractRegistry.filterOutputs(type, node.getData(), rawOutputs);
        String nodeId = node.getId();

        context.getScopedVariables().put(nodeId, new LinkedHashMap<>(filtered));
        context.getVariables().put(nodeId, new LinkedHashMap<>(filtered));

        for (Map.Entry<String, Object> entry : filtered.entrySet()) {
            if (!NodeIoContractRegistry.isDebugOnlyKey(entry.getKey())) {
                context.getVariables().put(entry.getKey(), entry.getValue());
            }
        }
    }
}
