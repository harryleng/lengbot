package com.lengbot.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ToolResultPrefixes;
import com.lengbot.entity.Tool;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * API 工具回调：将数据库中定义的 API 工具包装为 AgentScope ToolBase
 * <p>Agent 对话时 LLM 可直接调用此工具发起 HTTP 请求</p>
 *
 * @author finch
 * @since 2026-06-25
 */
@Slf4j
public class ApiToolCallback extends ToolBase {

    private final Tool tool;
    private final ApiToolExecutionService executionService;
    private final ObjectMapper objectMapper;

    public ApiToolCallback(Tool tool, ApiToolExecutionService executionService, ObjectMapper objectMapper) {
        super(ToolBase.builder()
                .name(tool.getName())
                .description(tool.getDescription() != null ? tool.getDescription() : "")
                .inputSchema(parseInputSchema(tool.getInputSchema(), objectMapper)));
        this.tool = tool;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        log.info("[ApiToolCallback] 执行API工具: name={}", tool.getName());
        try {
            String toolInput = extractArgsJson(param);
            Map<String, Object> inputs = parseInputs(toolInput);
            String result = executionService.execute(tool, inputs);
            log.info("[ApiToolCallback] API工具执行完成: name={}, resultLength={}", tool.getName(), result.length());
            return Mono.just(ToolResultBlock.of(null, tool.getName(), TextBlock.builder().text(result).build()));
        } catch (Exception e) {
            log.error("[ApiToolCallback] API工具执行异常: name={}, error={}", tool.getName(), e.getMessage(), e);
            return Mono.just(ToolResultBlock.error(ToolResultPrefixes.FAILURE + ": " + e.getMessage()));
        }
    }

    /**
     * 从 ToolCallParam 提取工具入参 JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private String extractArgsJson(ToolCallParam param) {
        if (param == null) return "{}";
        try {
            Object args = param.getInput();
            if (args == null) return "{}";
            if (args instanceof String s) return s;
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("[ApiToolCallback] 提取入参失败: error={}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 解析 inputSchema JSON 字符串为 Map
     */
    private static Map<String, Object> parseInputSchema(String inputSchemaJson, ObjectMapper objectMapper) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(inputSchemaJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 解析 LLM 传入的 JSON 参数
     */
    private Map<String, Object> parseInputs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ApiToolCallback] 解析输入参数失败: input={}, error={}", toolInput, e.getMessage());
            return Map.of();
        }
    }
}
