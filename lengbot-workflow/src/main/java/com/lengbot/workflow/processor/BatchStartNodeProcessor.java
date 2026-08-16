package com.lengbot.workflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.WorkflowSubgraphExecutor;
import org.springframework.stereotype.Component;

/**
 * 并行处理开始节点：仅作为子图内部的起始标记，不做任何业务处理
 * <p>容器的完整执行由 {@link BatchNodeProcessor} 负责，此节点仅在子图迭代中被跳过</p>
 *
 * @author finch
 * @since 2026-06-15
 */
@Component
public class BatchStartNodeProcessor extends AbstractGroupContainerProcessor {

    public BatchStartNodeProcessor(WorkflowSubgraphExecutor subgraphExecutor, ObjectMapper objectMapper) {
        super(subgraphExecutor, objectMapper);
    }

    @Override
    public NodeType getType() {
        return NodeType.BATCH_START;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        if (context.getParentNodeId() == null) {
            // 1. 兼容画布将主流程连到内置 batch_start 的图，入口处执行整个批处理容器。
            return executeContainer(context, NodeType.BATCH_START, NodeType.BATCH_END, true);
        }
        return passThrough(context, "output", "success");
    }
}
