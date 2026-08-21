package com.lengbot.util;

/**
 * 工具入参 JSON Schema 校验异常
 * <p>由 {@link ToolInputSchemaValidator} 抛出，message 已组装为对 LLM 友好的多行文本，
 * 装饰器捕获后结构化为 JSON 回喂给 LLM 触发重试</p>
 *
 * @author lw
 * @since 2026-07-20
 */
public class ToolValidationException extends RuntimeException {

    public ToolValidationException(String message) {
        super(message);
    }
}
