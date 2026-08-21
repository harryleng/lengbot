package com.lengbot.service.sandbox;

import com.lengbot.dto.CodeExecResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GraalVM Python 引擎（GraalPy）
 * <p>纯 Java 内嵌 Python 解释器，无需 OS 安装 Python；通过 Context 级隔离规避 subprocess 弱隔离问题</p>
 * <p>依赖 org.graalvm.python:python（optional）：未引入时 {@code isAvailable()} 返回 false，
 * EngineRegistry 自动降级到 {@link PythonEngine}（subprocess）。引入后优先级高于 subprocess 引擎</p>
 * <p>已知限制：GraalPy 对 numpy/pandas/scipy 等需要 C 扩展的库兼容性差，依赖这些库的场景请保留 subprocess PythonEngine</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraalVmPythonEngine implements CodeEngine {

    private final GraalVmSandbox sandbox;

    @Override
    public String language() {
        return "python";
    }

    @Override
    public boolean isAvailable() {
        return sandbox.isLanguageAvailable("python");
    }

    @Override
    public CodeExecResultDTO execute(String code, Map<String, Object> params, long timeoutMs) {
        return sandbox.execute("python", code, params, timeoutMs);
    }
}
