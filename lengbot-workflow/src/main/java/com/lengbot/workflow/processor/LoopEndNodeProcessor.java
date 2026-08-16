package com.lengbot.workflow.processor;

import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 迭代结束节点：标记单轮子图结束
 */
@Component
public class LoopEndNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    @Override
    public NodeType getType() {
        return NodeType.LOOP_END;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Object input = context.getVariables().getOrDefault("result", context.getVariables().get("output"));
        return passThrough(context, "result", input);
    }
}
