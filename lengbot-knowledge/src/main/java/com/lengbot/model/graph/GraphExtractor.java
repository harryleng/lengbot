package com.lengbot.model.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.dto.GraphTripleDTO;
import com.lengbot.model.ModelFactory;
import com.lengbot.util.ModelErrorClassifier;
import com.lengbot.util.Msgs;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;

/**
 * 知识图谱实体关系抽取器（基于 LLM）
 *
 * @author lw
 * @since 2026-05-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphExtractor {

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是一个知识图谱实体关系抽取专家。请从给定文本中抽取实体和关系三元组。

            要求：
            1. 实体：提取人名、组织、职位、项目、产品、地点、技术、概念等有明确含义的名词
            2. 关系：提取实体间的语义关系，用简洁的动词或动词短语描述（如"负责"、"隶属于"、"使用"、"包含"）
            3. 实体名称保持原文，不要翻译或缩写
            4. 关系方向要正确：A 做了某事指向 B，则 head=A, tail=B
            5. 如果文本中没有明确的实体关系，返回空数组 []
            6. 实体类型使用以下类别之一：人物、组织、职位、项目、产品、地点、技术、概念、事件、其他

            输出格式（纯 JSON 数组，不要任何额外文字或 markdown 格式）：
            [{"head":"实体A","headType":"类型","headDesc":"描述","relation":"关系","relationDesc":"关系描述","tail":"实体B","tailType":"类型","tailDesc":"描述"}]
            """;

    private static final String ENTITY_EXTRACT_PROMPT = """
            请从以下问题中提取关键实体名称（人名、组织、项目、技术、产品等有明确含义的名词）。
            只输出实体名称的 JSON 数组，不要任何额外文字。

            示例输入："张三负责哪个项目？"
            示例输出：["张三"]

            问题：%s
            """;

    /**
     * 从文本中抽取实体和关系三元组
     *
     * @param content    文本内容
     * @param providerId 模型提供商ID（为空时自动使用第一个可用提供商）
     * @param modelId    指定模型ID（为空时使用 provider 默认模型）
     * @return 三元组列表
     */
    public List<GraphTripleDTO> extract(String content, Long providerId, String modelId) {
        return extract(content, providerId, modelId, null, null);
    }

    /**
     * 从文本中抽取实体和关系三元组（支持 Schema 约束和模型参数）
     *
     * @param content    文本内容
     * @param providerId 模型提供商ID
     * @param modelId    指定模型ID
     * @param schema     Schema 约束文本（拼接到抽取 Prompt 尾部），可为 null
     * @param modelParams 模型参数（如 temperature、maxTokens），可为 null
     * @return 三元组列表
     */
    public List<GraphTripleDTO> extract(String content, Long providerId, String modelId,
                                         String schema, Map<String, Object> modelParams) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        // 截取前 2000 字符避免 token 超限
        String truncated = content.length() > 2000 ? content.substring(0, 2000) : content;

        try {
            Long actualProviderId = resolveProviderId(providerId);
            var ctx = modelFactory.getModelWithContext(actualProviderId, modelId, modelParams);

            String systemPrompt = buildSystemPrompt(schema);

            List<Msg> messages = new ArrayList<>();
            messages.add(Msgs.system(systemPrompt));
            messages.add(Msgs.user("请从以下文本中抽取实体和关系三元组：\n\n" + truncated
                    + "\n\n要求：紧凑 JSON 输出，不要换行、不要缩进、不要 markdown 代码块。"));

            long callStart = System.currentTimeMillis();
            log.info("[图谱抽取] 单块调用发起: providerId={}, modelId={}, options={}, inputChars={}, thread={}",
                    actualProviderId, modelId, describeOptions(ctx.options()), truncated.length(), Thread.currentThread().getName());
            var response = ctx.call(messages);
            long cost = System.currentTimeMillis() - callStart;
            logModelResponse("单块", response, cost, truncated.length());
            String text = Msgs.extractText(response);
            List<GraphTripleDTO> triples = parseTriples(text);
            log.info("[图谱抽取] 单块解析结果: triples={}, textChars={}, elapsedMs={}", triples.size(), text.length(), cost);
            return triples;
        } catch (Exception e) {
            if (ModelErrorClassifier.isFatal(e)) {
                throw ModelErrorClassifier.toRuntimeException(e);
            }
            log.warn("[图谱抽取] LLM 抽取失败: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 从用户问题中提取关键实体名（用于图谱检索）
     *
     * @param question   用户问题
     * @param providerId 模型提供商ID（为空时自动使用第一个可用提供商）
     * @return 实体名列表
     */
    public List<String> extractEntitiesFromQuestion(String question, Long providerId) {
        try {
            Long actualProviderId = resolveProviderId(providerId);
            var ctx = modelFactory.getModelWithContext(actualProviderId, null);

            List<Msg> messages = new ArrayList<>();
            messages.add(Msgs.user(ENTITY_EXTRACT_PROMPT.formatted(question)));

            var response = ctx.call(messages);
            String text = Msgs.extractText(response);
            return parseEntityNames(text);
        } catch (Exception e) {
            log.warn("[图谱抽取] 问题实体提取失败: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量抽取：将多个文本块合并为一次 LLM 调用，减少网络往返
     *
     * @param contents   文本内容列表（2-3个chunk）
     * @param providerId 模型提供商ID
     * @param modelId    指定模型ID（为空时使用 provider 默认模型）
     * @return 所有三元组的合并列表
     */
    public List<GraphTripleDTO> extractBatch(List<String> contents, Long providerId, String modelId) {
        return extractBatch(contents, providerId, modelId, null, null);
    }

    /**
     * 批量抽取（支持 Schema 约束和模型参数）
     *
     * @param contents   文本内容列表
     * @param providerId 模型提供商ID
     * @param modelId    指定模型ID
     * @param schema     Schema 约束文本，可为 null
     * @param modelParams 模型参数，可为 null
     * @return 所有三元组的合并列表
     */
    public List<GraphTripleDTO> extractBatch(List<String> contents, Long providerId, String modelId,
                                              String schema, Map<String, Object> modelParams) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }

        // 合并多个文本块，用分隔符分隔
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            String text = contents.get(i);
            if (text == null || text.isBlank()) continue;
            if (combined.length() > 0) {
                combined.append("\n\n=== 文本段落分隔 ===\n\n");
            }
            // 每个chunk最多2000字符
            combined.append(text.length() > 2000 ? text.substring(0, 2000) : text);
        }

        if (combined.length() == 0) {
            return Collections.emptyList();
        }

        // 总长度截断保护
        String finalText = combined.length() > 6000 ? combined.substring(0, 6000) : combined.toString();

        try {
            Long actualProviderId = resolveProviderId(providerId);
            var ctx = modelFactory.getModelWithContext(actualProviderId, modelId, modelParams);

            String systemPrompt = buildSystemPrompt(schema);

            List<Msg> messages = new ArrayList<>();
            messages.add(Msgs.system(systemPrompt));
            messages.add(Msgs.user("请从以下文本中抽取实体和关系三元组（文本包含多个段落，请分别抽取）：\n\n" + finalText
                    + "\n\n要求：紧凑 JSON 输出，不要换行、不要缩进、不要 markdown 代码块。"));

            long callStart = System.currentTimeMillis();
            log.info("[图谱抽取] 批量调用发起: providerId={}, modelId={}, options={}, segments={}, inputChars={}, thread={}",
                    actualProviderId, modelId, describeOptions(ctx.options()), contents.size(), finalText.length(), Thread.currentThread().getName());
            var response = ctx.call(messages);
            long cost = System.currentTimeMillis() - callStart;
            logModelResponse("批量", response, cost, finalText.length());
            String text = Msgs.extractText(response);
            List<GraphTripleDTO> triples = parseTriples(text);
            log.info("[图谱抽取] 批量解析结果: triples={}, textChars={}, elapsedMs={}", triples.size(), text.length(), cost);
            return triples;
        } catch (Exception e) {
            if (ModelErrorClassifier.isFatal(e)) {
                throw ModelErrorClassifier.toRuntimeException(e);
            }
            log.warn("[图谱抽取] 批量LLM抽取失败, 降级为逐个抽取: {}", e.toString(), e);
            // 降级：逐个抽取（仅非致命错误）
            List<GraphTripleDTO> fallback = new ArrayList<>();
            for (String content : contents) {
                fallback.addAll(extract(content, providerId, modelId, schema, modelParams));
            }
            return fallback;
        }
    }

    /**
     * 解析提供商ID，为空时自动使用第一个可用提供商
     */
    private Long resolveProviderId(Long providerId) {
        if (providerId != null) {
            return providerId;
        }
        List<Long> availableIds = modelFactory.getAvailableProviderIds();
        if (availableIds.isEmpty()) {
            throw new IllegalStateException("没有可用的模型提供商，请先配置模型");
        }
        return availableIds.get(0);
    }

    /**
     * 打印本次调用真正生效的生成参数，用于确认 maxTokens / temperature 是否下发。
     */
    private String describeOptions(GenerateOptions options) {
        if (options == null) {
            return "null(未显式设置模型参数)";
        }
        return "model=%s, maxTokens=%s, temperature=%s, topP=%s, stream=%s".formatted(
                options.getModelName(), options.getMaxTokens(), options.getTemperature(),
                options.getTopP(), options.getStream());
    }

    /**
     * 打印模型响应的关键指标，用于定位“无异常但零产出”的场景。
     */
    private void logModelResponse(String scene, ChatResponse response, long costMs, int inputChars) {
        StringBuilder textBuf = new StringBuilder();
        int thinkingChars = 0;
        int blockCount = 0;
        if (response != null && response.getContent() != null) {
            blockCount = response.getContent().size();
            for (ContentBlock block : response.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    textBuf.append(tb.getText());
                } else if (block instanceof ThinkingBlock thb && thb.getThinking() != null) {
                    thinkingChars += thb.getThinking().length();
                }
            }
        }
        String text = textBuf.toString();
        ChatUsage usage = response != null ? response.getUsage() : null;
        String finishReason = response != null ? response.getFinishReason() : null;
        String usageDesc = usage == null ? "null"
                : "in=%d,out=%d,total=%d".formatted(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
        String preview = text.length() > 200 ? text.substring(0, 200) : text;
        log.info("[图谱抽取] {} 响应: inputChars={}, elapsedMs={}, blocks={}, textChars={}, thinkingChars={}, finishReason={}, usage={}, preview={}",
                scene, inputChars, costMs, blockCount, text.length(), thinkingChars, finishReason, usageDesc,
                preview.replace("\n", "\\n"));
        if (text.isBlank()) {
            log.warn("[图谱抽取] {} 响应文本为空: thinkingChars={}, finishReason={}, usage={} —— 该批必然产出 0 条三元组",
                    scene, thinkingChars, finishReason, usageDesc);
        }
    }

    /**
     * 解析 LLM 返回的三元组 JSON
     */
    private List<GraphTripleDTO> parseTriples(String text) {
        if (text == null || text.isBlank()) {
            log.warn("[图谱抽取] 待解析文本为空，本次产出 0 条三元组（模型可能只输出思考内容，或输出被 maxTokens 截断）");
            return Collections.emptyList();
        }
        // 1) 剥离推理模型可能输出的 <think>...</think> / <thinking>...</thinking> 推理块（与 QA/评估抽取保持一致）
        String cleaned = text;
        cleaned = cleaned.replaceAll("(?is)<think>.*?</think>", "");
        cleaned = cleaned.replaceAll("(?is)<thinking>.*?</thinking>", "");
        // 2) 去除 markdown 代码块围栏
        String json = cleaned.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        // 3) 截取首个 '[' 到最后一个 ']' 之间的内容，兼容模型在 JSON 前后输出的引导语/收尾语
        int startBracket = json.indexOf('[');
        int endBracket = json.lastIndexOf(']');
        if (startBracket >= 0 && endBracket > startBracket) {
            json = json.substring(startBracket, endBracket + 1);
        }
        List<GraphTripleDTO> triples = new ArrayList<>();
        boolean truncated = false;
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                log.warn("[图谱抽取] 三元组解析失败：根元素不是 JSON 数组, text={}",
                        text.length() > 200 ? text.substring(0, 200) : text);
                return Collections.emptyList();
            }
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                Map<String, Object> raw;
                try {
                    raw = parser.readValueAs(Map.class);
                } catch (JsonProcessingException eof) {
                    // 单个元素结构异常（maxTokens 截断 EOF 或非对象元素）均停在此处，
                    // 保留已解析的完整三元组，避免一个脏元素导致整批归零
                    truncated = true;
                    log.info("[图谱抽取] 三元组解析在元素处中断，已抢救前序完整三元组: {}", eof.toString());
                    break;
                }
                GraphTripleDTO dto = toTripleDto(raw);
                if (dto != null) {
                    triples.add(dto);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("[图谱抽取] 三元组解析失败: text={}",
                    text.length() > 200 ? text.substring(0, 200) : text, e);
            return Collections.emptyList();
        } catch (IOException e) {
            log.info("[图谱抽取] 三元组解析 IO 异常: {}", e.toString());
            return Collections.emptyList();
        }
        if (truncated) {
            if (triples.isEmpty()) {
                log.warn("[图谱抽取] 三元组输出被截断，且无完整三元组可抢救（疑似 maxTokens 不足）: text={}",
                        text.length() > 200 ? text.substring(0, 200) : text);
            } else {
                log.warn("[图谱抽取] 三元组输出被截断，已抢救 {} 条完整三元组（疑似 maxTokens 不足）", triples.size());
            }
        }
        return triples;
    }

    private GraphTripleDTO toTripleDto(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        GraphTripleDTO dto = new GraphTripleDTO();
        dto.setHead(getString(raw, "head"));
        dto.setHeadType(getString(raw, "headType"));
        dto.setHeadDesc(getString(raw, "headDesc"));
        dto.setRelation(getString(raw, "relation"));
        dto.setRelationDesc(getString(raw, "relationDesc"));
        dto.setTail(getString(raw, "tail"));
        dto.setTailType(getString(raw, "tailType"));
        dto.setTailDesc(getString(raw, "tailDesc"));
        if (isBlank(dto.getHead()) || isBlank(dto.getRelation()) || isBlank(dto.getTail())) {
            log.info("[图谱抽取] 丢弃实体名或关系为空的三元组: head=[{}], relation=[{}], tail=[{}]",
                    dto.getHead(), dto.getRelation(), dto.getTail());
            return null;
        }
        return dto;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 解析 LLM 返回的实体名 JSON 数组
     */
    private List<String> parseEntityNames(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        try {
            String json = text.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.info("[图谱抽取] 实体名解析失败: text={}", text, e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建系统提示词，可选追加 Schema 约束
     */
    private String buildSystemPrompt(String schema) {
        if (schema == null || schema.isBlank()) {
            return EXTRACT_SYSTEM_PROMPT;
        }
        return EXTRACT_SYSTEM_PROMPT + "\n\n抽取 Schema 约束：\n" + schema.trim();
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : null;
    }
}