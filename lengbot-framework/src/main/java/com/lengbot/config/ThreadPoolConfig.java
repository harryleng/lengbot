package com.lengbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一线程池配置
 * <p>替代各 Service 中散落的 newCachedThreadPool / newFixedThreadPool，
 * 集中管理线程资源，防止高并发下线程数失控。</p>
 *
 * @author lw
 * @since 2026-06-24
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 共享有界线程池：RAG 检索、工具搜索等并发任务
     */
    @Primary
    @Bean(name = "lengBotExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor lengBotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("lengbot-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 子智能体的并行运行池。主聊天工具回调会同步等待其结果，不与
     * lengBotExecutor 共用，避免高并发下出现嵌套等待的线程饥饿。
     */
    @Bean(name = "subAgentExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor subAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("lengbot-subagent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

}
