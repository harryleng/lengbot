package com.lengbot.agent.harness;

import com.lengbot.service.chat.ToolEventGenerator;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * v2 {@link AgentEvent}（{@code HarnessAgent.streamEvents()} 产物） -> LengBot 主链路 SSE 协议桥接器。
 *
 * <p><b>职责边界（Phase 1 定稿）</b>：本桥接器只负责 <b>正文 token</b> 与 <b>思考流</b> 的映射，
 * 以及对应的 ctx 回填（敏感词过滤、fullReply/reasoningContent 累积）。<b>工具事件由
 * {@code LengBotToolExecutionWrapper}（ChatServiceImpl 内部类装饰器）接管</b>，对齐 legacy
 * {@code appendToolCallStart}/{@code appendToolCallResult} 语义（共享 toolCallId/contentOffset/
 * toolEventsList 持久化）。子 agent 事件留待 Phase 2 迁移 {@code SubAgentRuntime} 时处理。</p>
 *
 * <h3>为什么工具事件不在桥接器</h3>
 * v2 的 {@code TOOL_CALL_END} 在工具执行<b>前</b>触发，此时 toolCallId（DB 主键，执行时由
 * {@code IdWorker} 生成）尚未产生；且工具结果文本需执行后才知。legacy 由 {@code appendToolCallStart}
 * （执行前发 tool_call + 预生成 id）+ {@code appendToolCallResult}（执行后发 tool_result）统一编排，
 * id/contentOffset/持久化共享。桥接器无法复刻此编排，故交由装饰器在工具执行点统一处理。</p>
 *
 * <h3>v2 相比 v1 的改进</h3>
 * v2 的 {@link TextBlockDeltaEvent#getDelta()} / {@link ThinkingBlockDeltaEvent#getDelta()} 是<b>真增量</b>
 * （非累积全文），因此<b>不再需要</b> {@code ChatContext.consumeStreamTextDelta} 增量抽取，也不再需要
 * {@code InlineThinkingStreamParser}（v2 把 content 与 reasoning 分到独立事件）。敏感词直接作用于
 * {@code TEXT_BLOCK_DELTA} 增量（{@code SensitiveWordFilter.StreamState.processChunk} 内部维护累积态）。</p>
 *
 * <h3>输出协议</h3>
 * 与 {@code ChatStreamSseHelper} 消费的 {@code Flux<String>} 字节级一致：
 * <ul>
 *   <li>正文：{@code onContentDelta} 回调返回的字符串（裸文本，由回调做敏感过滤+fullReply 回填+可能的 sensitive_block）</li>
 *   <li>思考流：{@code [STATUS]{"type":"reasoning_content","content":"..."}}（{@code onReasoningDelta} 回调返回待下发的增量）</li>
 * </ul>
 * <b>不转义换行</b>（{@code ChatStreamSseHelper} 统一做）；<b>不发 {@code [DONE]}/{@code [REQUEST_ID]}</b>
 * （由 {@code buildDoneEvent}/{@code chatStream} 头部发）；<b>纯 mapper，错误上抛</b>（由 {@code streamCore}
 * 的 {@code onErrorResume} 转 error 帧）。</p>
 *
 * @author Senior Developer (LengBot refactor)
 * @since 1.0.0
 */
@Slf4j
@Component
public class AgentEventSseBridge {

    private final ToolEventGenerator toolEventGenerator;

    public AgentEventSseBridge(ToolEventGenerator toolEventGenerator) {
        this.toolEventGenerator = toolEventGenerator;
    }

    /** 默认桥接：正文/思考流透传（无敏感过滤、无 ctx 回填）。生产用 {@link #bridge(Flux, BridgeOptions)}。 */
    public Flux<String> bridge(Flux<AgentEvent> events) {
        return bridge(events, BridgeOptions.defaults());
    }

    /**
     * 把 v2 AgentEvent 流映射为主链路 SSE 协议字符串流。
     *
     * <p>无 per-subscription 可变状态（contentOffset 由装饰器从 ctx.fullReply 计算，不在桥接器），
     * 故无需 {@code Flux.defer} 隔离状态。</p>
     */
    public Flux<String> bridge(Flux<AgentEvent> events, BridgeOptions opts) {
        BridgeOptions o = opts != null ? opts : BridgeOptions.defaults();
        return events.concatMap(e -> Flux.fromIterable(handle(e, o)));
    }

    private List<String> handle(AgentEvent event, BridgeOptions opts) {
        if (event == null) {
            return List.of();
        }
        try {
            // 子 agent 事件（source 非空，方案 E source=parentSession/childId/sub-<uuid>）：
            // Phase 2 起交由 SubAgentEventBridge 归组映射 subagent_* SSE。
            String source = event.getSource();
            if (source != null && !source.isEmpty()) {
                return opts.onSubAgentEvent != null ? opts.onSubAgentEvent.apply(event) : List.of();
            }
            // 父流 agent_spawn 工具事件（harness 内部注册，不经 LengBot 装饰器）：交由桥接器
            // 注册 pending / 发 batch_done。其余工具的 TOOL_CALL_*/TOOL_RESULT_* 仍由装饰器接管。
            if (event instanceof ToolCallStartEvent || event instanceof ToolCallDeltaEvent
                    || event instanceof ToolCallEndEvent) {
                log.debug("[AgentEventBridge] tool event {} name={} id={} isSpawn={}",
                        event.getClass().getSimpleName(), toolNameOf(event), toolCallIdOf(event),
                        isAgentSpawnToolEvent(event));
            }
            if (opts.onSubAgentEvent != null
                    && (isAgentSpawnToolEvent(event) || event instanceof ToolCallDeltaEvent)) {
                // agent_spawn 的 args 经 __fragment__ delta 到达（raw fragment 名非 agent_spawn），
                // 一律路由给 session 由其按 parentToolCallId 状态过滤/累积（其余工具的 delta 被忽略）
                return opts.onSubAgentEvent.apply(event);
            }
            if (event instanceof TextBlockDeltaEvent d) {
                String delta = d.getDelta();
                if (delta == null || delta.isEmpty()) {
                    return List.of();
                }
                List<String> out = opts.onContentDelta.apply(delta);
                return out != null ? out : List.of();
            }
            if (event instanceof ThinkingBlockDeltaEvent d) {
                String delta = d.getDelta();
                if (delta == null || delta.isEmpty()) {
                    return List.of();
                }
                String out = opts.onReasoningDelta.apply(delta);
                if (out == null || out.isEmpty()) {
                    return List.of();
                }
                return List.of(ToolEventGenerator.STATUS_PREFIX + toolEventGenerator.reasoningEvent(out));
            }
            // TOOL_CALL_*/TOOL_RESULT_*（装饰器接管）、AGENT_END/RESULT、MODEL_CALL_*、
            // *_BLOCK_START/END、HINT 等：不产生 SSE 帧
            return List.of();
        } catch (Exception ex) {
            log.warn("[AgentEventBridge] 事件处理失败 type={} err={}",
                    event.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private static String toolNameOf(AgentEvent event) {
        if (event instanceof ToolCallStartEvent t) return t.getToolCallName();
        if (event instanceof ToolCallDeltaEvent t) return t.getToolCallName();
        if (event instanceof ToolCallEndEvent t) return t.getToolCallName();
        return null;
    }

    private static String toolCallIdOf(AgentEvent event) {
        if (event instanceof ToolCallStartEvent t) return t.getToolCallId();
        if (event instanceof ToolCallDeltaEvent t) return t.getToolCallId();
        if (event instanceof ToolCallEndEvent t) return t.getToolCallId();
        return null;
    }

    /** 是否父流 agent_spawn 工具事件（源 null，harness 内部调度，需桥接器注册/收尾）。 */
    private static boolean isAgentSpawnToolEvent(AgentEvent event) {
        if (event instanceof ToolCallStartEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolCallDeltaEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolCallEndEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolResultStartEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolResultTextDeltaEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolResultDataDeltaEvent t) return "agent_spawn".equals(t.getToolCallName());
        if (event instanceof ToolResultEndEvent t) return "agent_spawn".equals(t.getToolCallName());
        return false;
    }

    // ============================ 桥接选项 + 回调钩子 ============================

    /** 正文增量处理器：入参为 v2 真增量 delta，返回 0~N 条待下发协议字符串
     *  （由实现方做敏感词过滤、ctx.fullReply 回填、可能的 sensitive_block）。返回 null/空表示不下发。 */
    @FunctionalInterface
    public interface ContentDeltaHandler {
        List<String> apply(String delta);
    }

    /** 思考流增量处理器：入参为 v2 真增量 delta，返回待下发的增量（已做 ctx.reasoningContent 回填），
     *  返回 null/空表示不下发。 */
    @FunctionalInterface
    public interface ReasoningDeltaHandler {
        String apply(String delta);
    }

    /** 子 agent 事件处理器（source 非空 + 父流 agent_spawn 工具事件）：Phase 2 由
     *  {@code SubAgentEventBridge.Session#handle} 实现，内部经 SubAgentEventPublisher 直发 SSE。 */
    @FunctionalInterface
    public interface SubAgentEventHandler {
        List<String> apply(AgentEvent event);
    }

    /** 桥接行为选项。 */
    public static final class BridgeOptions {
        public final ContentDeltaHandler onContentDelta;
        public final ReasoningDeltaHandler onReasoningDelta;
        public final SubAgentEventHandler onSubAgentEvent;

        private BridgeOptions(ContentDeltaHandler onContentDelta, ReasoningDeltaHandler onReasoningDelta,
                              SubAgentEventHandler onSubAgentEvent) {
            this.onContentDelta = onContentDelta;
            this.onReasoningDelta = onReasoningDelta;
            this.onSubAgentEvent = onSubAgentEvent;
        }

        /** 默认：正文/思考流透传，无 ctx 回填，无子 agent 处理（供独立测试用）。 */
        public static BridgeOptions defaults() {
            return new BridgeOptions(delta -> List.of(delta), delta -> delta, null);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private ContentDeltaHandler onContentDelta = delta -> List.of(delta);
            private ReasoningDeltaHandler onReasoningDelta = delta -> delta;
            private SubAgentEventHandler onSubAgentEvent;

            public Builder onContentDelta(ContentDeltaHandler h) {
                this.onContentDelta = h != null ? h : (delta -> List.of(delta));
                return this;
            }

            public Builder onReasoningDelta(ReasoningDeltaHandler h) {
                this.onReasoningDelta = h != null ? h : (delta -> delta);
                return this;
            }

            public Builder onSubAgentEvent(SubAgentEventHandler h) {
                this.onSubAgentEvent = h;
                return this;
            }

            public BridgeOptions build() {
                return new BridgeOptions(onContentDelta, onReasoningDelta, onSubAgentEvent);
            }
        }
    }
}
