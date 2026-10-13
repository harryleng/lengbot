package com.lengbot.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lengbot.config.ElasticsearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 轻量 REST 封装（零新增依赖：JDK HttpClient + 项目已有 GSON）。
 * <p>仅承担 lexical(BM25) 召回，与向量/图检索在应用层经 RRF 融合。
 * 不使用官方 elasticsearch-java 客户端，规避本地 jackson 2.9.x 版本冲突（离线环境无法升级）。</p>
 *
 * @author lw
 * @since 2026-10-13
 */
@Component
public class ElasticsearchUtil {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchUtil.class);

    private final ElasticsearchProperties props;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final boolean available;

    public ElasticsearchUtil(ElasticsearchProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectionTimeoutMs()))
                .build();
        this.available = props.isEnabled()
                && props.getNodes() != null
                && !props.getNodes().isEmpty();
        if (available) {
            log.info("[ES] 已启用, nodes={}, analyzer={}", props.getNodes(), props.getAnalyzer());
        } else {
            log.info("[ES] 未启用(enabled=false 或 nodes 为空), 跳过 BM25 关键词检索");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private String baseUrl() {
        return props.getNodes().get(0).replaceAll("/$", "");
    }

    private String indexName(Long kbId) {
        return props.getIndexPrefix() + "_" + kbId;
    }

    private HttpRequest.Builder authBuilder(HttpRequest.Builder b) {
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            b.header("Authorization", "ApiKey " + props.getApiKey());
        } else if (props.getUsername() != null && !props.getUsername().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (props.getUsername() + ":" + (props.getPassword() == null ? "" : props.getPassword()))
                            .getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        b.header("Content-Type", "application/json");
        return b;
    }

    private String request(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        authBuilder(b);
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }

    public void createIndex(Long kbId) {
        if (!available) return;
        String idx = indexName(kbId);
        try {
            JsonObject settings = new JsonObject();
            settings.addProperty("number_of_shards", 1);
            settings.addProperty("number_of_replicas", 0);
            JsonObject propsObj = new JsonObject();
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("analyzer", props.getAnalyzer());
            JsonObject keyword = new JsonObject();
            keyword.addProperty("type", "keyword");
            propsObj.add("content", content);
            propsObj.add("document_id", keyword);
            propsObj.add("chunk_id", keyword);
            propsObj.add("knowledge_id", keyword);
            JsonObject mappings = new JsonObject();
            mappings.add("properties", propsObj);
            JsonObject root = new JsonObject();
            root.add("settings", settings);
            root.add("mappings", mappings);
            request("PUT", "/" + idx, root.toString());
            log.info("[ES] 创建索引成功: {}", idx);
        } catch (Exception e) {
            log.warn("[ES] 创建索引失败(可能已存在, 忽略): {}", e.getMessage());
        }
    }

    public void deleteIndex(Long kbId) {
        if (!available) return;
        try {
            request("DELETE", "/" + indexName(kbId), null);
            log.info("[ES] 删除索引: {}", indexName(kbId));
        } catch (Exception e) {
            log.warn("[ES] 删除索引失败: {}", e.getMessage());
        }
    }

    public void bulkIndexChunks(Long kbId, List<Map<String, Object>> chunks) {
        if (!available || chunks == null || chunks.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : chunks) {
            JsonObject action = new JsonObject();
            JsonObject meta = new JsonObject();
            meta.addProperty("_index", indexName(kbId));
            action.add("index", meta);
            sb.append(gson.toJson(action)).append("\n");
            JsonObject src = new JsonObject();
            src.addProperty("content", c.get("content") == null ? "" : String.valueOf(c.get("content")));
            Object did = c.get("document_id");
            Object cid = c.get("chunk_id");
            Object kid = c.get("knowledge_id");
            src.addProperty("document_id", did == null ? 0L : ((Number) did).longValue());
            src.addProperty("chunk_id", cid == null ? 0L : ((Number) cid).longValue());
            src.addProperty("knowledge_id", kid == null ? kbId : ((Number) kid).longValue());
            sb.append(gson.toJson(src)).append("\n");
        }
        try {
            String resp = request("POST", "/_bulk?refresh=true", sb.toString());
            if (resp != null && resp.contains("\"errors\":true")) {
                log.warn("[ES] bulk 写入存在部分失败: kbId={}", kbId);
            } else {
                log.info("[ES] bulk 写入成功: kbId={}, count={}", kbId, chunks.size());
            }
        } catch (Exception e) {
            log.warn("[ES] bulk 写入失败: {}", e.getMessage());
        }
    }

    public void deleteByDocumentId(Long kbId, Long documentId) {
        if (!available) return;
        JsonObject term = new JsonObject();
        JsonObject tid = new JsonObject();
        tid.addProperty("document_id", documentId);
        term.add("term", tid);
        JsonObject q = new JsonObject();
        q.add("query", term);
        try {
            request("POST", "/" + indexName(kbId) + "/_delete_by_query", q.toString());
            log.info("[ES] 按文档删除: kbId={}, docId={}", kbId, documentId);
        } catch (Exception e) {
            log.warn("[ES] 按文档删除失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> searchBm25(Long kbId, String queryText, int topK) {
        if (!available || queryText == null || queryText.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonObject field = new JsonObject();
            field.addProperty("query", queryText);
            field.addProperty("analyzer", props.getAnalyzer());
            JsonObject match = new JsonObject();
            match.add("content", field);
            JsonObject query = new JsonObject();
            query.add("match", match);
            JsonObject root = new JsonObject();
            root.add("query", query);
            root.addProperty("size", topK);
            JsonArray src = new JsonArray();
            src.add("content");
            src.add("document_id");
            src.add("chunk_id");
            root.add("_source", src);

            String resp = request("POST", "/" + indexName(kbId) + "/_search", root.toString());
            return parseHits(resp);
        } catch (Exception e) {
            log.warn("[ES] BM25 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> parseHits(String resp) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (resp == null || resp.isBlank()) return out;
        try {
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            JsonObject hits = root.getAsJsonObject("hits");
            JsonArray arr = hits.getAsJsonArray("hits");
            for (JsonElement el : arr) {
                JsonObject h = el.getAsJsonObject();
                JsonObject source = h.getAsJsonObject("_source");
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("content", source.has("content") ? source.get("content").getAsString() : "");
                map.put("document_id", source.has("document_id") ? source.get("document_id").getAsLong() : 0L);
                map.put("chunk_id", source.has("chunk_id") ? source.get("chunk_id").getAsLong() : 0L);
                map.put("score", h.has("_score") && !h.get("_score").isJsonNull() ? h.get("_score").getAsDouble() : 0.0);
                out.add(map);
            }
        } catch (Exception e) {
            log.warn("[ES] 解析 BM25 结果失败: {}", e.getMessage());
        }
        return out;
    }
}
