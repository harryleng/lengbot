package com.lengbot.service.impl;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * 纯 POST（无 SSE GET）的 streamable-http 客户端传输层。
 *
 * <p>企业微信 MCP 等服务器禁用 SSE（对 GET 返回 405 "SSE is disabled. Please use POST"），
 * 而标准 {@code HttpClientStreamableHttpTransport} 在初始化后会无条件建立一条 SSE GET 流
 * （{@code markInitialized} 首次调用无条件返回 true 触发 reconnect），导致握手失败。</p>
 *
 * <p>本传输层只发送 POST、解析 JSON-RPC 响应，不建立 SSE 流，兼容此类无状态服务器。</p>
 *
 * @author lw
 * @since 2026-08-26
 */
public class PostOnlyMcpTransport implements McpClientTransport {

    private final URI uri;
    private final McpJsonMapper jsonMapper;
    private final HttpClient httpClient;
    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

    public PostOnlyMcpTransport(String url) {
        this.uri = URI.create(url);
        this.jsonMapper = McpJsonMapper.getDefault();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        // 纯 POST 无服务器推送流，仅保存 handler 用于回传响应消息
        this.handler = handler;
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.create(sink -> {
            try {
                String body = jsonMapper.writeValueAsString(message);
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .header("Cache-Control", "no-cache")
                        .header("MCP-Protocol-Version", McpSchema.LATEST_PROTOCOL_VERSION)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                    if (err != null) {
                        sink.error(err);
                        return;
                    }
                    try {
                        String respBody = resp.body();
                        if (respBody != null && !respBody.isBlank()) {
                            McpSchema.JSONRPCMessage respMsg =
                                    McpSchema.deserializeJsonRpcMessage(jsonMapper, respBody);
                            if (handler != null) {
                                handler.apply(Mono.just(respMsg)).subscribe(null, sink::error, sink::success);
                            } else {
                                sink.success();
                            }
                        } else {
                            sink.success();
                        }
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    @Override
    public void close() {
        // HttpClient 无连接需显式关闭
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(McpSchema.LATEST_PROTOCOL_VERSION, "2025-03-26", "2024-11-05");
    }
}
