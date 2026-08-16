package com.lengbot.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ConfigKeys;
import com.lengbot.dto.ChatAttachmentDTO;
import com.lengbot.entity.ModelProvider;
import com.lengbot.service.chat.ToolCallAccumulator;
import com.lengbot.service.chat.ToolEventGenerator;
import com.lengbot.util.ChatDocumentMessageUtil;
import com.lengbot.util.MinioUtil;
import com.lengbot.util.Msgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ContentBlock;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiMo OpenAI 兼容 API 直连客户端
 * <p>用于联网搜索、多模态（图/视频）、thinking 等 Spring AI 未直接支持的参数</p>
 *
 * @see <a href="https://platform.xiaomimimo.com/docs/zh-CN/api/chat/openai-api">MiMo OpenAI API</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MimoChatClient {

    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    private final MinioUtil minioUtil;
    private final ObjectMapper objectMapper;
    private final ToolEventGenerator toolEventGenerator;

    /** RestClient 缓存：providerId → RestClient（避免每次请求重建连接） */
    private final ConcurrentHashMap<Long, RestClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 是否应使用 MiMo 直连（联网搜索或含视频附件）
     */
    public boolean shouldUseDirectApi(Map<String, Object> config, List<ChatAttachmentDTO> attachments) {
        if (Boolean.TRUE.equals(config.get(ConfigKeys.Agent.ENABLE_WEB_SEARCH))) {
            return true;
        }
        if (attachments != null) {
            for (ChatAttachmentDTO a : attachments) {
                if ("video".equals(a.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 流式对话
     */
    public Flux<String> streamChat(ModelProvider provider, Map<String, Object> config,
                                   List<Msg> messages, List<ChatAttachmentDTO> currentAttachments) {
        return Flux.create(sink -> {
            // 本流生命周期内的 tool_call args 累积器，流结束即丢弃，避免跨请求泄漏
            Map<Integer, ToolCallAccumulator> toolArgsAccumulators = new ConcurrentHashMap<>();
            try {
                Map<String, Object> body = buildRequestBody(provider, config, messages, currentAttachments, true);
                try {
                    log.info("[MimoChat] 请求体: {}", objectMapper.writeValueAsString(body));
                } catch (Exception ignored) {}
                RestClient client = buildClient(provider);
                client.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((req, res) -> {
                            if (!res.getStatusCode().is2xxSuccessful()) {
                                String errBody = "";
                                try (var errStream = res.getBody()) {
                                    if (errStream != null) {
                                        errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                                    }
                                } catch (Exception ignored) {
                                    // 忽略读取错误体失败
                                }
                                String hint = errBody.contains("webSearchEnabled")
                                        ? "（请确认已在 MiMo 控制台激活 Web Search Plugin: https://platform.xiaomimimo.com/#/console/plugin）" : "";
                                sink.error(new IllegalStateException(
                                        "MiMo API 错误: HTTP " + res.getStatusCode().value() + " " + errBody + hint));
                                return null;
                            }
                            try (var stream = res.getBody()) {
                                if (stream == null) {
                                    sink.complete();
                                    return null;
                                }
                                BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (!line.startsWith("data:")) {
                                        continue;
                                    }
                                    String data = line.substring(5).trim();
                                    if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                    parseStreamChunk(data, sink, toolArgsAccumulators);
                                }
                            } catch (Exception e) {
                                sink.error(e);
                                return null;
                            } finally {
                                toolArgsAccumulators.clear();
                            }
                            sink.complete();
                            return null;
                        });
            } catch (Exception e) {
                toolArgsAccumulators.clear();
                sink.error(e);
            }
        });
    }

    private void parseStreamChunk(String data, reactor.core.publisher.FluxSink<String> sink,
                                  Map<Integer, ToolCallAccumulator> toolArgsAccumulators) {
        try {
            JsonNode root = objectMapper.readTree(data);
            if (root.has("error") && !root.get("error").isNull()) {
                JsonNode err = root.get("error");
                String msg = err.has("message") ? err.get("message").asText() : err.toString();
                log.warn("[MimoChat] 流式错误块: {}", msg);
                sink.error(new IllegalStateException("MiMo API: " + msg));
                return;
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            // 部分场景（如联网搜索）在 message 节点返回正文
            if (choice.has("message") && !choice.get("message").isNull()) {
                emitTextContent(choice.get("message").get("content"), sink);
                emitReasoningContent(choice.get("message").get("reasoning_content"), sink);
            }
            if (delta == null) {
                return;
            }
            emitReasoningContent(delta.get("reasoning_content"), sink);
            emitTextContent(delta.get("content"), sink);
            // 提取 tool_calls[].function.arguments delta，节流推文件写入进度
            emitToolCallArgsDelta(delta.get("tool_calls"), sink, toolArgsAccumulators);
        } catch (Exception e) {
            log.debug("[MimoChat] 解析流式块失败: {}", e.getMessage());
        }
    }

    /**
     * 解析 delta.tool_calls，按 index 累积 arguments，节流推送「正在生成 · 已输出 N 字」
     *
     * @param toolCalls             delta.tool_calls 数组
     * @param sink                  上游 Flux sink
     * @param toolArgsAccumulators  本流累积器
     */
    private void emitToolCallArgsDelta(JsonNode toolCalls, reactor.core.publisher.FluxSink<String> sink,
                                       Map<Integer, ToolCallAccumulator> toolArgsAccumulators) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return;
        }
        for (JsonNode tc : toolCalls) {
            int index = tc.has("index") ? tc.get("index").asInt(0) : 0;
            ToolCallAccumulator acc = toolArgsAccumulators.computeIfAbsent(index, i -> {
                ToolCallAccumulator created = new ToolCallAccumulator();
                created.setToolCallKey(String.valueOf(i));
                return created;
            });
            JsonNode fn = tc.get("function");
            if (fn != null) {
                if (fn.has("name") && !fn.get("name").asText("").isBlank()) {
                    acc.setToolName(fn.get("name").asText());
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    acc.acceptArgsFragment(fn.get("arguments").asText(""));
                }
            }
            if (!ToolCallAccumulator.isFileWritingTool(acc.getToolName())) {
                continue;
            }
            acc.tryExtractPath();
            if (!acc.shouldPush()) {
                continue;
            }
            String basename = acc.basename();
            if (basename == null) {
                continue;
            }
            String msg = "正在生成 " + basename + " · 已输出 " + acc.getArgsLen() + " 字";
            sink.next("[STATUS]" + toolEventGenerator.toolStatusEvent(msg, 0));
            acc.markPushed();
        }
    }

    private void emitReasoningContent(JsonNode node, reactor.core.publisher.FluxSink<String> sink) {
        if (node == null || node.isNull()) {
            return;
        }
        String reasoning = node.asText("");
        if (!reasoning.isBlank()) {
            sink.next("[STATUS]" + toolEventGenerator.reasoningEvent(reasoning));
        }
    }

    /** 提取 content 字段（支持 string 或 array 多模态结构） */
    private void emitTextContent(JsonNode contentNode, reactor.core.publisher.FluxSink<String> sink) {
        if (contentNode == null || contentNode.isNull()) {
            return;
        }
        if (contentNode.isTextual()) {
            String text = contentNode.asText("");
            if (!text.isEmpty()) {
                sink.next(text);
            }
            return;
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part.has("text") && !part.get("text").isNull()) {
                    sb.append(part.get("text").asText(""));
                }
            }
            if (!sb.isEmpty()) {
                sink.next(sb.toString());
            }
        }
    }

    private Map<String, Object> buildRequestBody(ModelProvider provider, Map<String, Object> config,
                                                   List<Msg> messages, List<ChatAttachmentDTO> currentAttachments,
                                                   boolean stream) {
        Map<String, Object> body = new HashMap<>();
        Object modelIdObj = config.get("modelId");
        String modelId = modelIdObj != null && !modelIdObj.toString().isBlank()
                && !"unknown-model".equalsIgnoreCase(modelIdObj.toString().trim())
                ? modelIdObj.toString().trim() : "mimo-v2.5-pro";
        body.put("model", modelId);
        body.put("messages", toApiMessages(messages, currentAttachments));
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        if (config.containsKey("temperature")) {
            body.put("temperature", toDouble(config.get("temperature")));
        }
        if (config.containsKey("topP")) {
            body.put("top_p", toDouble(config.get("topP")));
        }
        if (config.containsKey("maxTokens")) {
            body.put("max_completion_tokens", toInt(config.get("maxTokens")));
        }
        if (config.containsKey("presencePenalty")) {
            body.put("presence_penalty", toDouble(config.get("presencePenalty")));
        }
        if (config.containsKey("frequencyPenalty")) {
            body.put("frequency_penalty", toDouble(config.get("frequencyPenalty")));
        }

        Map<String, Object> thinking = new HashMap<>();
        thinking.put("type", Boolean.TRUE.equals(config.get(ConfigKeys.Agent.ENABLE_REASONING))
                ? "enabled" : "disabled");
        body.put("thinking", thinking);

        if (Boolean.TRUE.equals(config.get(ConfigKeys.Agent.ENABLE_WEB_SEARCH))) {
            // MiMo 联网搜索：同时设置 webSearchEnabled 顶层参数 + tools 数组中的 web_search 类型
            body.put("webSearchEnabled", true);
            Map<String, Object> webSearch = new HashMap<>();
            webSearch.put("type", "web_search");
            if (config.containsKey(ConfigKeys.Agent.WEB_SEARCH_MAX_KEYWORD)) {
                webSearch.put("max_keyword", toInt(config.get(ConfigKeys.Agent.WEB_SEARCH_MAX_KEYWORD)));
            } else {
                webSearch.put("max_keyword", 3);
            }
            if (Boolean.TRUE.equals(config.get(ConfigKeys.Agent.WEB_SEARCH_FORCE))) {
                webSearch.put("force_search", true);
            }
            body.put("tools", List.of(webSearch));
            body.put("tool_choice", "auto");
        }

        return body;
    }

    private List<Map<String, Object>> toApiMessages(List<Msg> messages, List<ChatAttachmentDTO> currentAttachments) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean attachmentsApplied = false;
        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.SYSTEM) {
                list.add(Map.of("role", "system", "content", msg.getTextContent()));
            } else if (msg.getRole() == MsgRole.USER) {
                if (!attachmentsApplied && currentAttachments != null && !currentAttachments.isEmpty()) {
                    list.add(Map.of("role", "user", "content", buildMultimodalContent(msg.getTextContent(), currentAttachments)));
                    attachmentsApplied = true;
                } else {
                    list.add(Map.of("role", "user", "content", msg.getTextContent() != null ? msg.getTextContent() : ""));
                }
            } else if (msg.getRole() == MsgRole.ASSISTANT) {
                String text = msg.getTextContent();
                list.add(Map.of("role", "assistant", "content", text != null ? text : ""));
            }
        }
        return list;
    }

    private List<Map<String, Object>> buildMultimodalContent(String text, List<ChatAttachmentDTO> attachments) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(Map.of("type", "text", "text", text));
        }
        for (ChatAttachmentDTO att : attachments) {
            if (!ChatDocumentMessageUtil.isMediaAttachment(att)) {
                continue;
            }
            try {
                byte[] bytes = minioUtil.downloadBytes(att.getObjectKey());
                String mime = att.getMimeType() != null ? att.getMimeType() : "application/octet-stream";
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                if ("video".equals(att.getType())) {
                    Map<String, Object> videoPart = new HashMap<>();
                    videoPart.put("type", "video_url");
                    videoPart.put("video_url", Map.of("url", dataUrl));
                    videoPart.put("fps", 2);
                    videoPart.put("media_resolution", "default");
                    parts.add(videoPart);
                } else {
                    parts.add(Map.of(
                            "type", "image_url",
                            "image_url", Map.of("url", dataUrl)));
                }
            } catch (Exception e) {
                log.warn("[MimoChat] 附件转 base64 失败: {}", e.getMessage());
            }
        }
        if (parts.isEmpty()) {
            parts.add(Map.of("type", "text", "text", "请描述附件内容。"));
        }
        return parts;
    }

    private RestClient buildClient(ModelProvider provider) {
        return clientCache.computeIfAbsent(provider.getId(), id -> {
            String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                    ? provider.getBaseUrl().replaceAll("/+$", "") : DEFAULT_BASE_URL;
            if (!baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl + "/v1";
            }
            MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
            headers.add("api-key", provider.getApiKey());
            String finalBaseUrl = baseUrl;
            return RestClient.builder()
                    .baseUrl(finalBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + provider.getApiKey())
                    .defaultHeaders(h -> h.addAll(headers))
                    .build();
        });
    }

    /**
     * 清除指定 Provider 的 RestClient 缓存（凭证变更时调用）
     */
    public void clearClientCache(Long providerId) {
        clientCache.remove(providerId);
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    private int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }
}
