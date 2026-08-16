package com.lengbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨域配置：@Bean CorsFilter + 最高优先级（HIGHEST_PRECEDENCE）。
 *
 * 为什么设最高优先级：项目引入 Sa-Token，其全局 SaTokenFilter 注册顺序靠前；
 * 若依赖 Spring 自动/默认的 CorsFilter 或 addCorsMappings，容易被其它过滤器
 * 抢先以"空 CORS 配置"判定 Invalid CORS request。这里显式将此 CorsFilter
 * 置于最前，确保带 Origin 的请求第一时间被正确处理。
 *
 * 白名单 origin（CSV，逗号分隔），默认包含：
 *   - 5173：旧 LengBot(8081) 的前端 dev 端口
 *   - 5174：lengbot(8082) 的前端 dev 端口（lengbot-ui 默认）
 *   - 3000：常见前端 dev 端口
 *
 * @author finch
 * @since 2026-07-19
 */
@Configuration
public class CorsConfig {

    @Value("${lengbot.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:3000}")
    private String allowedOriginsCsv;

    @Value("${lengbot.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethodsCsv;

    @Value("${lengbot.cors.max-age:3600}")
    private long maxAge;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = parseOrigins(allowedOriginsCsv);
        config.setAllowedOrigins(origins);
        for (String origin : origins) {
            config.addAllowedOrigin(origin.trim());
        }

        // 同时用 allowedOriginPatterns，兼容 Spring 6.x 对具体 origin 的匹配
        for (String origin : origins) {
            config.addAllowedOriginPattern(origin.trim());
        }

        // 允许携带凭证（cookie / Authorization），需配合具体 origin（非 "*"）
        config.setAllowCredentials(true);

        for (String method : parseOrigins(allowedMethodsCsv)) {
            config.addAllowedMethod(method.trim());
        }

        config.addAllowedHeader("*");
        config.addExposedHeader("X-Trace-Id");
        config.addExposedHeader("X-Request-Id");
        config.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        // 用 FilterRegistrationBean 显式设置 order，确保绝对优先于其它过滤器
        bean.setOrder(-100);
        bean.setName("lengbotCorsFilter");
        System.out.println("[CorsConfig] CorsFilter registered (order=-100), allowedOrigins=" + origins);
        return bean;
    }

    private List<String> parseOrigins(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
