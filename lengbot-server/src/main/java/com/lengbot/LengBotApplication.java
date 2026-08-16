package com.lengbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * LengBot 主启动类。
 * <p>
 * LengBot 是基于 Spring Cloud Alibaba + AgentScope Java 构建的企业级 AI Agent 平台。
 * 采用 Maven 多模块单体架构，集成 Nacos 服务注册与配置中心，AI 引擎使用 AgentScope Java 替代 Spring AI。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@SpringBootApplication
@EnableDiscoveryClient  // 注册到 Nacos 服务发现中心
public class LengBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(LengBotApplication.class, args);
    }
}
