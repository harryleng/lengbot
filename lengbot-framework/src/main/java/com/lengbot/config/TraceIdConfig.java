package com.lengbot.config;

import com.lengbot.filter.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * TraceId 过滤器注册：拦截所有 {@code /api/**} 请求写入 MDC + 响应头
 * <p>Order 置于 {@link Ordered#HIGHEST_PRECEDENCE} 最高优先级，确保后续过滤器和 Controller 的日志都能拿到 traceId。</p>
 *
 * @author lw
 * @since 2026-07-21
 */
@Configuration
public class TraceIdConfig {

    /**
     * 注册 TraceId 过滤器
     *
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
