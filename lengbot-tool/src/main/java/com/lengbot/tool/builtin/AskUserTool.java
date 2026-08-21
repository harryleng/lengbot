package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.tool.ToolEventEmitter;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 — 向用户提问。
 * <p>兼容单题参数，并支持一次收集最多五个独立问题的回答。</p>
 *
 * @author lw
 * @since 2026-06-17
 */
@Slf4j
@Component("askUserTool")
@SystemTool(displayName = "向用户提问", icon = "MessageOutlined", description = "向用户提问并等待回答，用于确认信息或请求补充说明", tags = {"交互"},
        outputExample = "{\"question\":\"请问您想查询哪个时间段的数据？\",\"options\":[\"最近7天\",\"最近30天\",\"自定义\"],\"is_open_ended\":false,\"wait_for_user\":true,\"break_loop\":true}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"提出的问题\"},\"options\":{\"type\":\"array\",\"description\":\"选项列表（空数组表示开放式提问）\",\"items\":{\"type\":\"string\"}},\"questions\":{\"type\":\"array\",\"description\":\"多问题列表\"},\"is_open_ended\":{\"type\":\"boolean\",\"description\":\"是否为开放式提问\"},\"wait_for_user\":{\"type\":\"boolean\",\"description\":\"标记此工具需要等待用户回答\"},\"break_loop\":{\"type\":\"boolean\",\"description\":\"标记工具执行后应中断循环\"},\"answer\":{\"type\":\"string\",\"description\":\"用户回答（工作流 HITL resume 后注入，工具执行阶段为空）\"}}}")
@RequiredArgsConstructor
public class AskUserTool {

    public static final String TOOL_NAME = "ask_user";

    private final ObjectMapper objectMapper;

    /**
     * @param question 单题兼容参数；未传 questions 时使用
     * @param options 单题选项，逗号分隔
     * @param questions 1 到 5 个问题的 JSON 数组
     * @return 统一的提问结果
     */
    @Tool(name = TOOL_NAME, description = "向用户提问并等待回答。优先使用 questions 传入 1 到 5 个问题的 JSON 数组；"
            + "每项包含 questionId、question、options、multiSelect、allowOther。"
            + "旧的 question 与 options 参数仍可用于单问题。调用后必须等待用户回答，不能继续调用其他工具。")
    public String askUser(
            @ToolParam(name = "question", description = "旧版单问题；未传 questions 时必填", required = false)
            @ToolParamMeta(example = "请选择报告风格", required = false) String question,
            @ToolParam(name = "options", description = "旧版选项，逗号分隔", required = false)
            @ToolParamMeta(example = "简洁,详细", required = false) String options,
            @ToolParam(name = "questions", description = "1 到 5 个问题的 JSON 数组", required = false)
            @ToolParamMeta(example = "[{\"questionId\":\"style\",\"question\":\"请选择报告风格\",\"options\":[\"简洁\",\"详细\"],\"multiSelect\":false,\"allowOther\":true}]", required = false)
            String questions) {
        List<Map<String, Object>> normalizedQuestions = normalizeQuestions(questions, question, options);
        if (normalizedQuestions.isEmpty()) {
            return "{\"_error\":true,\"message\":\"问题内容不能为空\"}";
        }
        ToolEventEmitter.emit("等待用户回答...");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("questions", normalizedQuestions);
        Map<String, Object> first = normalizedQuestions.get(0);
        // 保留旧字段，兼容历史单题消息与既有前端渲染。
        output.put("question", first.get("question"));
        output.put("options", first.get("options"));
        output.put("is_open_ended", first.get("is_open_ended"));
        output.put("wait_for_user", true);
        output.put("break_loop", true);
        try {
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("[Tool:ask_user] 序列化问题失败", e);
            return "{\"_error\":true,\"message\":\"问题序列化失败\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeQuestions(String questionsJson, String question, String options) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (questionsJson != null && !questionsJson.isBlank()) {
            try {
                Object raw = objectMapper.readValue(questionsJson, Object.class);
                if (raw instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> normalized = normalizeQuestion((Map<String, Object>) map, result.size() + 1);
                            if (!String.valueOf(normalized.get("question")).isBlank()) {
                                result.add(normalized);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Tool:ask_user] questions 参数解析失败: {}", e.getMessage());
            }
        }
        if (result.isEmpty() && question != null && !question.isBlank()) {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("question", question);
            legacy.put("options", splitOptions(options));
            legacy.put("multiSelect", false);
            legacy.put("allowOther", true);
            result.add(normalizeQuestion(legacy, 1));
        }
        return result.size() > 5 ? new ArrayList<>(result.subList(0, 5)) : result;
    }

    private Map<String, Object> normalizeQuestion(Map<String, Object> source, int index) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object rawId = source.get("questionId");
        result.put("questionId", rawId == null || String.valueOf(rawId).isBlank()
                ? "question_" + index : String.valueOf(rawId).trim());
        result.put("question", source.get("question") == null ? "" : String.valueOf(source.get("question")).trim());
        List<String> options = source.get("options") instanceof List<?> values
                ? values.stream().filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(value -> String.valueOf(value).trim()).limit(5).toList()
                : splitOptions(source.get("options") == null ? null : String.valueOf(source.get("options")));
        result.put("options", options);
        result.put("multiSelect", Boolean.TRUE.equals(source.get("multiSelect")));
        result.put("allowOther", !Boolean.FALSE.equals(source.get("allowOther")));
        result.put("is_open_ended", options.isEmpty());
        return result;
    }

    private List<String> splitOptions(String options) {
        if (options == null || options.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String option : options.split(",")) {
            if (option != null && !option.isBlank()) {
                result.add(option.trim());
            }
        }
        return result.size() > 5 ? new ArrayList<>(result.subList(0, 5)) : result;
    }
}
