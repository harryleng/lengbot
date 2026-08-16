package com.lengbot.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 子工作流执行结果
 */
@Data
@Builder
public class SubWorkflowExecutionResult {

    private String output;

    private Map<String, Object> variables;

    private Map<String, Object> nodeOutputs;
}
