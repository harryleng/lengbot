package com.lengbot.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.subagent.service.SubAgentTaskService;
import com.lengbot.subagent.spi.SubAgentDefinition;
import com.lengbot.subagent.spi.SubAgentDefinitionResolver;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SubAgent 对外工具门面；编排、查询和取消逻辑均委托给 {@link SubAgentTaskService}。
 *
 * @author lw
 * @since 2026-07-10
 */
@Component
@RequiredArgsConstructor
public class DelegateSubAgentTool {

    public static final String TOOL_NAME = "delegate_to_subagent";
    public static final String RESULT_TOOL_NAME = "get_subagent_task_result";
    public static final String CANCEL_TOOL_NAME = "cancel_subagent_task";

    private final ObjectMapper objectMapper;
    private final SubAgentDefinitionResolver definitionResolver;
    private final SubAgentTaskService subAgentTaskService;

    /** 为兼容旧调用返回第一个委派工具。 */
    public ToolBase buildCallback(List<Long> boundSubAgentIds) {
        List<ToolBase> callbacks = buildCallbacks(boundSubAgentIds);
        return callbacks.isEmpty() ? null : callbacks.get(0);
    }

    /**
     * 构造当前 Agent 的同步委派门面。
     * <p>委派调用会等待任务终态并把结果直接回填给主 Agent，因此不再暴露查询/取消
     * 工具给模型，避免结果已可用时仍主动轮询。历史事件和管理端接口仍保持兼容。</p>
     */
    public List<ToolBase> buildCallbacks(List<Long> boundSubAgentIds) {
        Map<String, SubAgentDefinition> definitions = definitionResolver.resolve(boundSubAgentIds);
        if (definitions.isEmpty()) return List.of();
        return List.of(new TaskCallback(definitions.values(), boundSubAgentIds, Operation.DELEGATE));
    }

    private String delegateDescription(Iterable<SubAgentDefinition> definitions) {
        List<SubAgentDefinition> items = java.util.stream.StreamSupport.stream(definitions.spliterator(), false).toList();
        String names = items.stream().map(SubAgentDefinition::name).map(this::json).collect(Collectors.joining(", "));
        String catalog = items.stream().map(item -> "- " + item.name() + "（" + item.displayName() + "）")
                .collect(Collectors.joining("\\n"));
        return """
                将自包含任务委派给一个或多个 SubAgent。mode=sync 按顺序等待；mode=parallel 并行等待。
                仅支持 sync、parallel；父 Agent 会等待每项任务到达终态，并拿到最终 reply 后继续本轮生成。
                各任务的 reply 会原样全部返回，框架不做自动汇总；合并与总结由你（主 Agent）在拿到结果后自行阅读完成。
                可用 SubAgent：
                """ + catalog;
    }

    private String delegateInputSchema(Iterable<SubAgentDefinition> definitions) {
        List<SubAgentDefinition> items = java.util.stream.StreamSupport.stream(definitions.spliterator(), false).toList();
        String names = items.stream().map(SubAgentDefinition::name).map(this::json).collect(Collectors.joining(", "));
        return """
                {"type":"object","properties":{
                  "mode":{"type":"string","enum":["sync","parallel"],"default":"sync"},
                  "subagent_name":{"type":"string","enum":[%s]},"task":{"type":"string"},"thread_id":{"type":"string"},
                  "tasks":{"type":"array","items":{"type":"object","properties":{"subagent_name":{"type":"string","enum":[%s]},"task":{"type":"string"},"thread_id":{"type":"string"}},"required":["subagent_name","task"]}},
                  "max_concurrency":{"type":"integer","minimum":1,"maximum":5},
                  "aggregation":{"type":"string","enum":["return_all"],"default":"return_all",
                    "description":"当前仅支持 return_all：各子任务结果原样返回，由主 Agent 自行阅读并综合"}
                }}
                """.formatted(names, names);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ToolBase.Builder delegateToolBuilder(Iterable<SubAgentDefinition> definitions) {
        return ToolBase.builder()
                .name(TOOL_NAME)
                .description(delegateDescription(definitions))
                .inputSchema(parseSchema(delegateInputSchema(definitions)));
    }

    private String json(String value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception ignored) { return "\"\""; }
    }

    private enum Operation { DELEGATE, QUERY, CANCEL }

    private class TaskCallback extends ToolBase {
        private final List<Long> boundSubAgentIds;
        private final Operation operation;

        TaskCallback(Iterable<SubAgentDefinition> definitions, List<Long> boundSubAgentIds, Operation operation) {
            super(delegateToolBuilder(definitions));
            this.boundSubAgentIds = boundSubAgentIds;
            this.operation = operation;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String toolInput = extractArgsJson(param);
            String result = switch (operation) {
                case DELEGATE -> subAgentTaskService.delegate(toolInput, param, boundSubAgentIds);
                case QUERY -> subAgentTaskService.query(toolInput, param);
                case CANCEL -> subAgentTaskService.cancel(toolInput, param);
            };
            return Mono.just(ToolResultBlock.of(null, getName(),
                    TextBlock.builder().text(result).build()));
        }

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
    }
}
