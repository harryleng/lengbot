package com.lengbot.util;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Neo4j 图数据库工具类
 * <p>封装 Neo4j Driver 操作，供 GraphService 调用</p>
 * <p>懒加载：首次使用时才初始化 Driver，无 Neo4j 服务时不影响项目启动</p>
 *
 * @author lw
 * @since 2026-05-29
 */
@Slf4j
@Component
public class Neo4jUtil {

    private final String uri;
    private final String username;
    private final String password;
    /**
     * 连接失败后的自动重试冷却时间（毫秒）。
     * 失败不"钉死"：冷却期内直接判定不可用，避免每个请求都重连产生网络开销；
     * 冷却期过后允许重新探测，使 Neo4j 晚启动 / 瞬时抖动后能自动恢复，无需重启后端。
     */
    private static final long RETRY_COOLDOWN_MS = 60_000L;
    private volatile Driver driver;
    private volatile boolean initialized = false;
    private volatile boolean available = false;
    private volatile long lastFailureMillis = 0L;

    public Neo4jUtil(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password) {
        this.uri = uri;
        this.username = username;
        this.password = password;
        log.info("[Neo4j] 配置已加载, uri={}（懒初始化，首次使用时连接）", uri);
    }

    /**
     * 获取 Driver（懒初始化 + 失败自动重试，不永久钉死不可用）
     * <p>设计要点：
     * 1. 首次成功后将 available=true 并长期缓存，后续请求零开销；
     * 2. 连接失败时记录失败时间戳并进入"冷却期"，冷却期内直接返回 null（不重试），
     *    避免每个图谱请求都触发一次失败的网络连接；
     * 3. 冷却期过后允许重新探测，Neo4j 晚启动 / 瞬时抖动恢复后无需重启后端即可自愈；
     * 4. 额外提供 {@link #reconnect()} 供运维主动立即重连。
     */
    private Driver getDriver() {
        // 快速路径：已成功初始化，直接返回，无锁开销
        if (initialized && available) {
            return driver;
        }
        synchronized (this) {
            // 双重检查
            if (initialized && available) {
                return driver;
            }
            // 曾经失败过：冷却期内不重试，直接判定不可用
            if (initialized && !available
                    && System.currentTimeMillis() - lastFailureMillis < RETRY_COOLDOWN_MS) {
                return null;
            }
            // 冷却期已过（或从未初始化）：重置状态重新尝试连接
            if (initialized && !available) {
                initialized = false;
            }
            if (!initialized) {
                try {
                    this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
                    // 验证连接
                    try (Session session = driver.session()) {
                        session.run("RETURN 1").consume();
                    }
                    this.available = true;
                    log.info("[Neo4j] 连接成功, uri={}", uri);
                } catch (Exception e) {
                    this.available = false;
                    this.lastFailureMillis = System.currentTimeMillis();
                    log.warn("[Neo4j] 连接失败，图谱功能暂不可用（{}s 冷却后自动重试）: {}",
                            RETRY_COOLDOWN_MS / 1000, e.getMessage());
                } finally {
                    // 无论成败都标记已探测过，失败态进入冷却逻辑
                    this.initialized = true;
                }
            }
            return driver;
        }
    }

    /**
     * Neo4j 是否可用
     */
    public boolean isAvailable() {
        getDriver();
        return available;
    }

    /**
     * 主动重连（供运维在 Neo4j 晚启动 / 恢复后调用，无需重启后端）。
     * 关闭旧 Driver、清空探测状态，下次 {@link #getDriver()} 会重新建立连接并验证。
     */
    public void reconnect() {
        synchronized (this) {
            if (driver != null) {
                try {
                    driver.close();
                } catch (Exception ignore) {
                    // 关闭失败不影响后续重连
                }
            }
            this.driver = null;
            this.initialized = false;
            this.available = false;
            this.lastFailureMillis = 0L;
            log.info("[Neo4j] 已触发主动重连，下次访问将重新探测连接");
        }
    }

    /**
     * 执行写事务
     */
    public <T> T executeWrite(TransactionCallback<T> callback) {
        Driver drv = getDriver();
        if (drv == null) {
            throw new IllegalStateException("Neo4j 不可用");
        }
        try (Session session = drv.session()) {
            return session.executeWrite(callback);
        }
    }

    /**
     * 执行读事务
     */
    public <T> T executeRead(TransactionCallback<T> callback) {
        Driver drv = getDriver();
        if (drv == null) {
            throw new IllegalStateException("Neo4j 不可用");
        }
        try (Session session = drv.session()) {
            return session.executeRead(callback);
        }
    }

    /**
     * 执行单条 Cypher 语句（无返回值）
     */
    public void run(String cypher, Map<String, Object> params) {
        executeWrite(tx -> {
            tx.run(cypher, params);
            return null;
        });
    }

    /**
     * 执行查询，返回 Neo4j Record 列表
     */
    public List<org.neo4j.driver.Record> query(String cypher, Map<String, Object> params) {
        return executeRead(tx -> {
            Result result = tx.run(cypher, params != null ? params : Map.of());
            List<org.neo4j.driver.Record> records = new ArrayList<>();
            while (result.hasNext()) {
                records.add(result.next());
            }
            return records;
        });
    }

    /**
     * 执行写事务并返回 Record 列表（MERGE/CREATE 等带 RETURN 的写操作）
     */
    public List<org.neo4j.driver.Record> queryWrite(String cypher, Map<String, Object> params) {
        return executeWrite(tx -> {
            Result result = tx.run(cypher, params != null ? params : Map.of());
            List<org.neo4j.driver.Record> records = new ArrayList<>();
            while (result.hasNext()) {
                records.add(result.next());
            }
            return records;
        });
    }

    /**
     * 将 Neo4j Node 转为 Map
     */
    public Map<String, Object> nodeToMap(Node node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("elementId", node.elementId());
        List<String> labels = new ArrayList<>();
        node.labels().forEach(label -> labels.add(label.toString()));
        map.put("labels", labels);
        node.keys().forEach(key -> map.put(key, node.get(key).asObject()));
        return map;
    }

    /**
     * 将 Neo4j Relationship 转为 Map
     */
    public Map<String, Object> relToMap(Relationship rel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("elementId", rel.elementId());
        map.put("type", rel.type());
        map.put("startNodeElementId", rel.startNodeElementId());
        map.put("endNodeElementId", rel.endNodeElementId());
        rel.keys().forEach(key -> map.put(key, rel.get(key).asObject()));
        return map;
    }

    /**
     * 健康检查
     */
    public boolean ping() {
        try {
            executeRead(tx -> {
                tx.run("RETURN 1").consume();
                return null;
            });
            return true;
        } catch (Exception e) {
            log.warn("[Neo4j] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取知识库的 Label 名称
     */
    public static String kbLabel(Long knowledgeId) {
        return "kb_" + knowledgeId;
    }

    /** 独立图谱标签（全局，不关联知识库） */
    public static final String STANDALONE_LABEL = "standalone";

    @PreDestroy
    public void close() {
        if (driver != null) {
            try {
                driver.close();
                log.info("[Neo4j] Driver 已关闭");
            } catch (Exception e) {
                log.warn("[Neo4j] 关闭 Driver 异常", e);
            }
        }
    }
}
