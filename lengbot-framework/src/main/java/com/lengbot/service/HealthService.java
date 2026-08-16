package com.lengbot.service;

import com.lengbot.util.MinioUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础设施健康聚合服务
 * <p>统一聚合 PostgreSQL / Redis / MinIO 三方连通性，供健康检查接口和 Actuator 复用。
 * 编排逻辑下沉到 Service，Controller 仅做透传。</p>
 *
 * @author finch
 * @since 2026-07-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final MinioUtil minioUtil;

    /**
     * 聚合健康检查
     *
     * @return {status: UP|DEGRADED, components: {postgresql/redis/minio: {status, responseTime, error?}}}
     */
    public Map<String, Object> aggregate() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean allUp = true;

        // 1. PostgreSQL
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(3);
            components.put("postgresql", Map.of("status", "UP", "responseTime",
                    System.currentTimeMillis() - start + "ms"));
        } catch (Exception e) {
            components.put("postgresql", Map.of("status", "DOWN", "error", e.getMessage()));
            allUp = false;
        }

        // 2. Redis
        start = System.currentTimeMillis();
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            components.put("redis", Map.of("status", "UP", "responseTime",
                    System.currentTimeMillis() - start + "ms"));
        } catch (Exception e) {
            components.put("redis", Map.of("status", "DOWN", "error", e.getMessage()));
            allUp = false;
        }

        // 3. MinIO
        start = System.currentTimeMillis();
        try {
            if (minioUtil.checkHealth()) {
                components.put("minio", Map.of("status", "UP", "responseTime",
                        System.currentTimeMillis() - start + "ms"));
            } else {
                components.put("minio", Map.of("status", "DOWN", "error", "Bucket does not exist"));
                allUp = false;
            }
        } catch (Exception e) {
            components.put("minio", Map.of("status", "DOWN", "error", e.getMessage()));
            allUp = false;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allUp ? "UP" : "DEGRADED");
        result.put("components", components);
        return result;
    }
}
