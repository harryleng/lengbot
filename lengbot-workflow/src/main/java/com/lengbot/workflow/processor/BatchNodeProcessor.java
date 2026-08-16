package com.lengbot.workflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.WorkflowSubgraphExecutor;
import org.springframework.stereotype.Component;

/**
 * 批处理容器节点：并行/批量执行子图
 */
@Component
public class BatchNodeProcessor extends AbstractGroupContainerProcessor {

    public BatchNodeProcessor(WorkflowSubgraphExecutor subgraphExecutor, ObjectMapper objectMapper) {
        super(subgraphExecutor, objectMapper);
    }

    @Override
    public NodeType getType() {
        return NodeType.BATCH;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        return executeContainer(context, NodeType.BATCH_START, NodeType.BATCH_END, true);
    }
}
