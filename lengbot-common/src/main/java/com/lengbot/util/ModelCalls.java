package com.lengbot.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 模型同步调用工具类
 *
 * <p>AgentScope 的 {@link Model} 接口只暴露 {@code stream(...)} 流式方法，且底层各厂商实现默认
 * 以 SSE 增量（delta）分片下发。若直接使用 {@code blockLast()} 只能拿到最后一个分片，
 * 会丢失前面全部内容。本工具类负责把整条流正确聚合成一个完整的 {@link ChatResponse}：</p>
 *
 * <ul>
 *   <li>文本块（TextBlock）按流顺序拼接为完整文本</li>
 *   <li>思考块（ThinkingBlock）单独拼接，保留在结果首位</li>
 *   <li>工具调用块（ToolUseBlock）按 id 归并，后到分片覆盖同 id 的旧分片</li>
 *   <li>用量（ChatUsage）与结束原因（finishReason）取流中最后一个非空值</li>
 * </ul>
 *
 * <p>用于替换原 Spring AI 中 {@code chatModel.call(prompt)} 的同步语义。</p>
 *
 * @author finch
 * @since 2026-08-01
 */
public final class ModelCalls {

    private ModelCalls() {
    }

    // ==================== 同步调用 ====================

    /**
     * 同步调用模型（无工具、无额外参数）
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @return 聚合后的完整响应
     */
    public static ChatResponse call(Model model, List<Msg> messages) {
        return call(model, messages, null, null);
    }

    /**
     * 同步调用模型（带生成参数）
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @param options  生成参数，可为 null
     * @return 聚合后的完整响应
     */
    public static ChatResponse call(Model model, List<Msg> messages, GenerateOptions options) {
        return call(model, messages, null, options);
    }

    /**
     * 同步调用模型（带工具与生成参数）
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @param tools    工具 Schema 列表，可为 null
     * @param options  生成参数，可为 null
     * @return 聚合后的完整响应
     */
    public static ChatResponse call(Model model, List<Msg> messages,
                                    List<ToolSchema> tools, GenerateOptions options) {
        return callAsync(model, messages, tools, options).block();
    }

    /**
     * 同步调用模型并直接返回纯文本内容
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @return 响应文本（已 trim，永不为 null）
     */
    public static String callText(Model model, List<Msg> messages) {
        return text(call(model, messages));
    }

    /**
     * 同步调用模型并直接返回纯文本内容（带生成参数）
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @param options  生成参数，可为 null
     * @return 响应文本（已 trim，永不为 null）
     */
    public static String callText(Model model, List<Msg> messages, GenerateOptions options) {
        return text(call(model, messages, options));
    }

    // ==================== 异步调用 ====================

    /**
     * 异步调用模型，返回聚合后的完整响应
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @param tools    工具 Schema 列表，可为 null
     * @param options  生成参数，可为 null
     * @return Mono&lt;ChatResponse&gt;
     */
    public static Mono<ChatResponse> callAsync(Model model, List<Msg> messages,
                                               List<ToolSchema> tools, GenerateOptions options) {
        List<ToolSchema> safeTools = tools != null ? tools : List.of();
        GenerateOptions safeOptions = options != null ? options : GenerateOptions.builder().build();
        return model.stream(messages, safeTools, safeOptions)
                .collectList()
                .map(ModelCalls::aggregate);
    }

    /**
     * 异步调用模型（无工具）
     *
     * @param model    AgentScope 模型实例
     * @param messages 消息列表
     * @param options  生成参数，可为 null
     * @return Mono&lt;ChatResponse&gt;
     */
    public static Mono<ChatResponse> callAsync(Model model, List<Msg> messages, GenerateOptions options) {
        return callAsync(model, messages, null, options);
    }

    // ==================== 流聚合 ====================

    /**
     * 将流式分片列表聚合为一个完整响应
     *
     * @param chunks 流式分片列表
     * @return 聚合后的完整响应；分片为空时返回空内容响应
     */
    public static ChatResponse aggregate(List<ChatResponse> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return ChatResponse.builder().content(List.of()).build();
        }
        if (chunks.size() == 1) {
            return chunks.get(0);
        }

        StringBuilder textBuf = new StringBuilder();
        StringBuilder thinkingBuf = new StringBuilder();
        // 按 id 归并工具调用（同 id 后到覆盖先到，保持首次出现顺序）
        Map<String, ToolUseBlock> toolUses = new LinkedHashMap<>();
        List<ContentBlock> others = new ArrayList<>();

        String id = null;
        ChatUsage usage = null;
        String finishReason = null;
        Map<String, Object> metadata = null;

        // 判定分片是增量还是累积：若后一分片文本以前一分片文本为前缀且更长，视为累积模式
        boolean cumulative = detectCumulative(chunks);

        for (ChatResponse chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            if (chunk.getId() != null) {
                id = chunk.getId();
            }
            if (chunk.getUsage() != null) {
                usage = chunk.getUsage();
            }
            if (chunk.getFinishReason() != null) {
                finishReason = chunk.getFinishReason();
            }
            if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
                metadata = chunk.getMetadata();
            }

            List<ContentBlock> blocks = chunk.getContent();
            if (blocks == null) {
                continue;
            }
            String chunkText = null;
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    String t = tb.getText();
                    if (t == null || t.isEmpty()) {
                        continue;
                    }
                    chunkText = chunkText == null ? t : chunkText + t;
                } else if (block instanceof ThinkingBlock thb) {
                    String t = thb.getThinking();
                    if (t != null && !t.isEmpty()) {
                        thinkingBuf.append(t);
                    }
                } else if (block instanceof ToolUseBlock tub) {
                    mergeToolUse(toolUses, tub);
                } else {
                    others.add(block);
                }
            }

            if (chunkText != null) {
                if (cumulative) {
                    // 累积模式：直接以最新分片为准
                    textBuf.setLength(0);
                    textBuf.append(chunkText);
                } else {
                    textBuf.append(chunkText);
                }
            }
        }

        List<ContentBlock> merged = new ArrayList<>();
        if (thinkingBuf.length() > 0) {
            merged.add(ThinkingBlock.builder().thinking(thinkingBuf.toString()).build());
        }
        if (textBuf.length() > 0) {
            merged.add(TextBlock.builder().text(textBuf.toString()).build());
        }
        merged.addAll(toolUses.values());
        merged.addAll(others);

        ChatResponse.Builder builder = ChatResponse.builder().content(merged);
        if (id != null) {
            builder.id(id);
        }
        if (usage != null) {
            builder.usage(usage);
        }
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        if (metadata != null) {
            builder.metadata(metadata);
        }
        return builder.build();
    }

    /**
     * 探测流式分片是否为“累积模式”（每片都包含此前全部文本）
     *
     * <p>多数厂商为增量（delta）模式，少数实现会累积下发。若检测到后一分片文本严格以
     * 前一分片文本为前缀且更长，则判定为累积模式，避免重复拼接。</p>
     *
     * @param chunks 流式分片列表
     * @return true 表示累积模式
     */
    private static boolean detectCumulative(List<ChatResponse> chunks) {
        String prev = null;
        int compared = 0;
        for (ChatResponse chunk : chunks) {
            String t = plainText(chunk);
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (prev != null) {
                // 增量模式下第二片通常不会以第一片全文为前缀；这里额外要求长度增量明显，
                // 避免单字符增量（如 "a" -> "aa"）被误判为累积
                if (!(t.length() > prev.length() && t.startsWith(prev) && prev.length() >= 2)) {
                    return false;
                }
                compared++;
            }
            prev = t;
        }
        // 至少连续两次比较均满足前缀关系，才认定为累积模式
        return compared >= 2;
    }

    /**
     * 归并同 id 的工具调用分片
     *
     * @param acc   累积容器
     * @param block 新到分片
     */
    private static void mergeToolUse(Map<String, ToolUseBlock> acc, ToolUseBlock block) {
        String key = block.getId() != null ? block.getId()
                : (block.getName() != null ? block.getName() : "tool_" + acc.size());
        ToolUseBlock prev = acc.get(key);
        if (prev == null) {
            acc.put(key, block);
            return;
        }
        // 后到分片信息更完整时覆盖；否则保留原值
        boolean newerHasInput = block.getInput() != null && !block.getInput().isEmpty();
        boolean newerHasContent = block.getContent() != null && !block.getContent().isEmpty();
        if (newerHasInput || newerHasContent) {
            acc.put(key, block);
        }
    }

    // ==================== 结果提取 ====================

    /**
     * 提取响应中的纯文本内容（已 trim）
     *
     * @param response 响应对象，可为 null
     * @return 文本内容，永不为 null
     */
    public static String text(ChatResponse response) {
        String t = plainText(response);
        return t == null ? "" : t.trim();
    }

    /**
     * 提取响应中的原始文本内容（不 trim）
     *
     * @param response 响应对象，可为 null
     * @return 文本内容，可能为 null
     */
    private static String plainText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 提取响应中的思考内容
     *
     * @param response 响应对象，可为 null
     * @return 思考文本，无则返回空串
     */
    public static String thinking(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ThinkingBlock tb && tb.getThinking() != null) {
                sb.append(tb.getThinking());
            }
        }
        return sb.toString();
    }

    /**
     * 提取响应中的工具调用块
     *
     * @param response 响应对象，可为 null
     * @return 工具调用列表，永不为 null
     */
    public static List<ToolUseBlock> toolUses(ChatResponse response) {
        List<ToolUseBlock> result = new ArrayList<>();
        if (response == null || response.getContent() == null) {
            return result;
        }
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock tub) {
                result.add(tub);
            }
        }
        return result;
    }

    // ==================== 向量类型转换 ====================

    /**
     * double[] 转 float[]（AgentScope 嵌入返回 double[]，pgvector/Milvus 侧使用 float[]）
     *
     * @param src 源数组，可为 null
     * @return 目标数组，源为 null 时返回 null
     */
    public static float[] toFloatArray(double[] src) {
        if (src == null) {
            return null;
        }
        float[] dst = new float[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = (float) src[i];
        }
        return dst;
    }

    /**
     * float[] 转 double[]
     *
     * @param src 源数组，可为 null
     * @return 目标数组，源为 null 时返回 null
     */
    public static double[] toDoubleArray(float[] src) {
        if (src == null) {
            return null;
        }
        double[] dst = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i];
        }
        return dst;
    }

    /**
     * List&lt;double[]&gt; 批量转 List&lt;float[]&gt;
     *
     * @param src 源列表，可为 null
     * @return 目标列表，源为 null 时返回 null
     */
    public static List<float[]> toFloatArrays(List<double[]> src) {
        if (src == null) {
            return null;
        }
        List<float[]> dst = new ArrayList<>(src.size());
        for (double[] v : src) {
            dst.add(toFloatArray(v));
        }
        return dst;
    }
}
