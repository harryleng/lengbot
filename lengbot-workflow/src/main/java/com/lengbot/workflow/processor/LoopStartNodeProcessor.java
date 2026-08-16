package com.lengbot.workflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.WorkflowSubgraphExecutor;
import org.springframework.stereotype.Component;

/**
 * 迭代开始节点：仅作为子图内部的起始标记，不做任何业务处理
 * <p>容器的完整执行由 {@link LoopNodeProcessor} 负责，此节点仅在子图迭代中被跳过</p>
 *
 * @author finch
 * @since 2026-06-15
 */
@Component
public class LoopStartNodeProcessor extends AbstractGroupContainerProcessor {

    public LoopStartNodeProcessor(WorkflowSubgraphExecutor subgraphExecutor, ObjectMapper objectMapper) {
        super(subgraphExecutor, objectMapper);
    }

    @Override
    public NodeType getType() {
        return NodeType.LOOP_START;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        if (context.getParentNodeId() == null) {
            // 1. 兼容画布将主流程连到内置 loop_start 的图，入口处执行整个循环容器。
            return executeContainer(context, NodeType.LOOP_START, NodeType.LOOP_END, false);
        }
        return passThrough(context, "output", "success");
    }
}
