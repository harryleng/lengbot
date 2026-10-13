package com.lengbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Elasticsearch 连接配置（仅用于真 BM25 关键词召回，不参与向量检索）。
 * <p>enabled=false 时 ElasticsearchUtil 所有方法空操作，不影响现有 Milvus/pgvector 行为。</p>
 *
 * @author lw
 * @since 2026-10-13
 */
@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {

    /** 是否启用 ES BM25 检索（默认关闭，需部署 ES 并装中文分词插件后开启） */
    private boolean enabled = false;

    /** ES 节点地址列表，取第一个作为读写入口 */
    private List<String> nodes = List.of("http://localhost:9200");

    /** ApiKey 鉴权（与 username/password 二选一，优先） */
    private String apiKey;

    /** Basic 鉴权用户名 */
    private String username;

    /** Basic 鉴权密码 */
    private String password;

    /** 索引时分词器（IK 插件：ik_max_word 索引 / ik_smart 查询）。需 ES 服务端安装 analysis-ik */
    private String analyzer = "ik_max_word";

    /** 索引前缀，最终 index = {prefix}_{knowledgeId} */
    private String indexPrefix = "kb";

    /** 连接超时(ms) */
    private long connectionTimeoutMs = 3000;

    /** 请求超时(ms) */
    private long requestTimeoutMs = 10000;
}
