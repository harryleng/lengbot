package com.lengbot.service.sandbox;

import com.lengbot.util.MinioUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * 沙盒文件系统后端装配：按 {@code lengbot.sandbox.backend} 切换实现。
 * <pre>
 *   lengbot.sandbox.backend: minio   → {@link MinioSandboxFs}（默认，需 MinIO 可达）
 *   lengbot.sandbox.backend: local   → {@link LocalDiskSandboxFs}（本地磁盘，无外部依赖）
 * </pre>
 *
 * <p>通过 {@link ObjectProvider} 延迟获取 {@link MinioUtil}：backend=local 时不创建
 * MinioUtil Bean，也不会触发其 {@code @PostConstruct} 的 MinIO 连接，可完全离线运行。</p>
 *
 * @author lw
 * @since 2026-08-21
 */
@Slf4j
@Configuration
public class SandboxFsConfig {

    @Bean
    public SandboxFs sandboxFs(Environment env,
                               ObjectProvider<MinioUtil> minioUtilProvider,
                               @Value("${lengbot.sandbox.local-root:./data/sandbox}") String localRoot,
                               @Value("${lengbot.sandbox.public-base-url:}") String publicBaseUrl) {
        String backend = env.getProperty("lengbot.sandbox.backend", "minio");
        if ("local".equalsIgnoreCase(backend)) {
            log.info("[SandboxFs] 使用本地磁盘后端, local-root={}", localRoot);
            return new LocalDiskSandboxFs(Path.of(localRoot), publicBaseUrl);
        }
        MinioUtil minioUtil = minioUtilProvider.getIfAvailable();
        if (minioUtil == null) {
            throw new IllegalStateException("lengbot.sandbox.backend=minio 但 MinioUtil Bean 不可用");
        }
        log.info("[SandboxFs] 使用 MinIO 后端");
        return new MinioSandboxFs(minioUtil);
    }
}
