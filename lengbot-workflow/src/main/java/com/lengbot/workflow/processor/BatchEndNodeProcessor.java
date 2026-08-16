package com.lengbot.workflow.processor;

import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import org.springframework.stereotype.Component;

/**
 * 并行结束节点：标记单批子图结束
 */
@Component
public class BatchEndNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    @Override
    public NodeType getType() {
        return NodeType.BATCH_END;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Object input = context.getVariables().getOrDefault("result", context.getVariables().get("output"));
        return passThrough(context, "result", input);
    }
}
