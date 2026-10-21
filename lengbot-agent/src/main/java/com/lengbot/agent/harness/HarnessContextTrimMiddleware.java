package com.lengbot.agent.harness;

import com.lengbot.util.ChatMessageContextUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Harness 引擎路径的上下文裁剪中间件。
 *
 * <p><b>背景</b>：{@link HarnessAgentFactory} 里显式 {@code disableCompaction()} +
 * {@code disableToolResultEviction()}，把引擎自带的上下文压缩关掉，理由是「由 LengBot 自管」。
 * 但 LengBot 自己的 {@code trimToolCallContext} 只接在 legacy 的
 * {@code processToolCallsRecursively} / {@code processBlockingRound} 两条路径上，
 * harness 引擎路径<b>完全没接</b> —— 结果 harness 分支的上下文压缩处于真空：谁都不压。</p>
 *
 * <p>典型后果：agent 分段写几十 KB 的文件时，每轮 {@code local_write_file} /
 * {@code local_append_file} 的完整入参都留在历史消息里逐轮累积，跑 20+ 轮后累计输入可达
 * 12 万 tokens（日志实测 {@code tokens=121752/856}），进而更容易触发上游 SSE
 * {@code Connection reset}。</p>
 *
 * <p><b>挂载点</b>：实现 {@link MiddlewareBase#onReasoning}，在<b>每次模型推理前</b>对当轮
 * messages 做一次裁剪。reasoning 是每轮工具循环必经的钩子，因此等价于 legacy 路径
 * 「每轮工具调用后裁剪」的时机。</p>
 *
 * <p><b>安全性</b>：不原地修改引擎传入的 messages，而是拷贝后再交给
 * {@link ChatMessageContextUtil#trimToolCallContext(List, int, int)}。因此：
 * <ul>
 *   <li>引擎内部 memory 保持完整，checkpoint / 落库 / 前端回显不受影响；</li>
 *   <li>只有「发给模型的 prompt」被压缩，正是要降的成本；</li>
 *   <li>裁剪异常一律 catch 后回退原始 input，绝不阻断主链路。</li>
 * </ul>
 */
@Slf4j
public class HarnessContextTrimMiddleware implements MiddlewareBase {

    /** 字符上限，超出才动手；与 legacy 路径阈值保持一致。 */
    private final int maxChars;
    /** 压缩后保留最近 N 轮完整工具调用，确保 LLM 仍有足够上下文。 */
    private final int roundsToKeep;

    /**
     * 仅用于首次 reasoning 打印挂载确认：没有这条日志就无法区分
     * 「中间件未挂载」与「已挂载但未达阈值」，排障成本很高。
     * 每次请求新建本中间件（见 streamViaHarness），故该标记天然按请求隔离。
     */
    private final AtomicBoolean mountLogged = new AtomicBoolean(false);

    public HarnessContextTrimMiddleware(int maxChars, int roundsToKeep) {
        this.maxChars = maxChars > 0 ? maxChars : 60000;
        this.roundsToKeep = roundsToKeep > 0 ? roundsToKeep : 2;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (input == null || input.messages() == null || input.messages().size() < 3) {
            return next.apply(input);
        }
        try {
            // 拷贝后裁剪：不污染引擎 memory，只压缩本轮真正发给模型的 prompt
            List<Msg> copy = new ArrayList<>(input.messages());
            if (mountLogged.compareAndSet(false, true)) {
                log.info("[ContextTrim][Harness] 中间件已挂载: messages={}, chars={}, 阈值={}chars/保留{}轮",
                        copy.size(), ChatMessageContextUtil.estimateTotalChars(copy), maxChars, roundsToKeep);
            }
            // harness 的 inputMessages 不允许含 SYSTEM 消息，故摘要降级为 USER 角色
            ChatMessageContextUtil.trimToolCallContext(copy, maxChars, roundsToKeep, MsgRole.USER);
            return next.apply(new ReasoningInput(copy, input.tools(), input.options()));
        } catch (Exception e) {
            log.warn("[ContextTrim][Harness] 裁剪失败，回退原始上下文: {}", e.getMessage());
            return next.apply(input);
        }
    }
}
