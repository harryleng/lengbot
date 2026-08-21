package com.lengbot.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ToolResultPrefixes;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 工具入参 JSON Schema 校验装饰器
 * <p>包装原始 {@link ToolBase}，在 callAsync 前用 {@link ToolInputSchemaValidator} 校验入参，
 * 校验失败时返回结构化错误 JSON 回喂给 LLM 触发重试，避免非法参数进入工具导致 NPE 或脏数据</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Slf4j
public class ValidatingToolCallback extends ToolBase {

    private final ToolBase delegate;
    private final ToolInputSchemaValidator validator;
    private final ObjectMapper objectMapper;

    public ValidatingToolCallback(ToolBase delegate, ToolInputSchemaValidator validator) {
        super(ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters()));
        this.delegate = delegate;
        this.validator = validator;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 提取入参 JSON 用于校验
        String toolInput = extractArgsJson(param);
        // 2. 校验入参，非法时直接返回错误 JSON（不抛异常，避免被外层兜底成 500）
        try {
            String inputSchema = serializeSchema(delegate.getParameters());
            validator.validate(inputSchema, toolInput);
        } catch (ToolValidationException e) {
            String toolName = delegate.getName();
            log.warn("[ToolValidator] 工具入参校验失败: tool={}, reason={}", toolName, e.getMessage());
            return Mono.just(ToolResultBlock.of(null, toolName,
                    TextBlock.builder().text(ToolResultPrefixes.failureJson(e.getMessage())).build()));
        }
        // 3. 校验通过，委托原始回调执行
        return delegate.callAsync(param);
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
            return "{}";
        }
    }

    /**
     * 将 inputSchema Map 序列化为 JSON 字符串（供 validator 使用）
     */
    private String serializeSchema(java.util.Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }
}
