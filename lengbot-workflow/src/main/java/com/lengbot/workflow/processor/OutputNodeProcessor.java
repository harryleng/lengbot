package com.lengbot.workflow.processor;

import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.WorkflowChatExposure;
import com.lengbot.workflow.WorkflowPromptUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程输出节点：渲染 output 模板作为工作流输出
 */
@Slf4j
@Component
public class OutputNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    @Override
    public NodeType getType() {
        return NodeType.OUTPUT;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> nodeData = context.getCurrentNodeData() != null
                ? context.getCurrentNodeData() : Map.of();
        String template = String.valueOf(nodeData.getOrDefault("output", "{{input}}"));
        String output = WorkflowPromptUtils.render(template, context);

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("output", output);

        log.info("[OutputNodeProcessor] 输出节点完成: nodeId={}, length={}",
                context.getCurrentNodeId(), output.length());

        String chatContent = WorkflowChatExposure.isOutputStreamSwitchEnabled(nodeData) ? output : null;

        return NodeExecutionResult.builder()
                .nextNodeId(resolveNextNodeId(context))
                .outputs(outputs)
                .streamContent(chatContent)
                .finished(false)
                .build();
    }
}
