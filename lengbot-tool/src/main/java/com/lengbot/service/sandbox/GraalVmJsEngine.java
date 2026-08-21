package com.lengbot.service.sandbox;

import com.lengbot.dto.CodeExecResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GraalVM JavaScript 引擎
 * <p>优先级高于 Nashorn：GraalVM JS 支持 ES2020+，且通过 Context 级资源限制形成强隔离，
 * 解决 Nashorn (JDK15+ 移除) 与 ES5 语法限制问题。GraalVM 依赖未引入时
 * {@link GraalVmSandbox#isLanguageAvailable(String)} 返回 false，EngineRegistry 自动降级到 Nashorn</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraalVmJsEngine implements CodeEngine {

    private final GraalVmSandbox sandbox;

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public boolean isAvailable() {
        return sandbox.isLanguageAvailable("js");
    }

    @Override
    public CodeExecResultDTO execute(String code, Map<String, Object> params, long timeoutMs) {
        return sandbox.execute("js", code, params, timeoutMs);
    }
}
