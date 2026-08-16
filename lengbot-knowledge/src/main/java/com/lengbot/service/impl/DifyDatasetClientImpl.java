package com.lengbot.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.config.DifyProperties;
import com.lengbot.enums.ErrorCode;
import com.lengbot.service.DifyDatasetClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dify Dataset REST 客户端实现。 */
@Slf4j
@Component
public class DifyDatasetClientImpl implements DifyDatasetClient {

    private final ObjectMapper objectMapper;
    private final DifyProperties properties;
    private final HttpClient httpClient;

    public DifyDatasetClientImpl(ObjectMapper objectMapper, DifyProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<Map<String, Object>> retrieve(String apiUrl, String datasetId, String token,
                                               String query, int topK, double threshold, String searchMode) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("query", query);
            requestBody.put("retrieval_model", Map.of(
                    "search_method", toDifySearchMethod(searchMode),
                    "reranking_enable", false,
                    "top_k", topK,
                    "score_threshold_enabled", threshold > 0,
                    "score_threshold", threshold));
            String encodedDatasetId = URLEncoder.encode(datasetId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalizeApiUrl(apiUrl)
                            + "/datasets/" + encodedDatasetId + "/retrieve"))
                    .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[Dify] Dataset 检索失败: status={}, datasetId={}", response.statusCode(), datasetId);
                throw new BizException(ErrorCode.DIFY_CONNECTION_FAILED);
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() { });
            Object records = body.get("records");
            if (!(records instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Dify] Dataset 检索请求异常: datasetId={}, reason={}", datasetId, e.getClass().getSimpleName());
            throw new BizException(ErrorCode.DIFY_CONNECTION_FAILED, e);
        }
    }

    private String normalizeApiUrl(String apiUrl) {
        String normalized = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
        if (!normalized.endsWith("/v1")) {
            throw new BizException(ErrorCode.DIFY_CONFIG_INVALID);
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.DIFY_CONFIG_INVALID);
        }
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || "localhost".equalsIgnoreCase(uri.getHost())) {
            throw new BizException(ErrorCode.DIFY_CONFIG_INVALID);
        }
        return normalized;
    }

    private String toDifySearchMethod(String searchMode) {
        return switch (searchMode) {
            case "keyword" -> "keyword_search";
            case "hybrid" -> "hybrid_search";
            default -> "semantic_search";
        };
    }
}
