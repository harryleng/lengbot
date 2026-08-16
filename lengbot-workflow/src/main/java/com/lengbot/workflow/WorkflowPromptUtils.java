package com.lengbot.workflow;

import java.util.Map;

/**
 * 工作流 Prompt 模板渲染
 */
public final class WorkflowPromptUtils {

    private WorkflowPromptUtils() {
    }

    /**
     * 基于扁平 variables 渲染（兼容旧调用）
     */
    public static String render(String template, Map<String, Object> variables) {
        return WorkflowReferenceResolver.renderWithVariables(template, variables);
    }

    /**
     * 基于完整上下文渲染，支持 {{nodeId.field}} / ${nodeId.field} / {{sys.query}}
     */
    public static String render(String template, NodeExecutionContext context) {
        return WorkflowReferenceResolver.renderWithContext(template, context);
    }
}
