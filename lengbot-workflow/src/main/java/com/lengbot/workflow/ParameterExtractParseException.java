package com.lengbot.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数提取节点 JSON 解析失败时携带模型原始回复，供 trace / 前端展示
 */
public class ParameterExtractParseException extends IllegalArgumentException {

    private final String rawResponse;

    /**
     * @param message     面向用户的错误说明
     * @param rawResponse 模型原始输出
     * @param cause       解析异常
     */
    public ParameterExtractParseException(String message, String rawResponse, Throwable cause) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    /**
     * 从异常链中解析参数提取解析异常
     */
    public static ParameterExtractParseException unwrap(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ParameterExtractParseException ex) {
                return ex;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 构建仅用于可观测性的事件数据（不写入变量池）
     */
    public static NodeExecutionResult toObservabilityResult(Throwable error) {
        ParameterExtractParseException ex = unwrap(error);
        if (ex == null || ex.getRawResponse() == null || ex.getRawResponse().isBlank()) {
            return null;
        }
        Map<String, Object> observability = new LinkedHashMap<>();
        observability.put("extractRaw", ex.getRawResponse());
        return NodeExecutionResult.builder()
                .outputs(new LinkedHashMap<>(observability))
                .traceData(new LinkedHashMap<>(observability))
                .build();
    }
}
