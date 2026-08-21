package com.lengbot.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.dto.CodeExecResultDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GraalVM Polyglot 沙盒
 * <p>纯 Java 内嵌的多语言执行器：单 {@link Engine} 复用，每次调用新建独立 {@link Context}，
 * 通过 {@link HostAccess#NONE} + {@link IOAccess#NONE} + 资源限制形成强隔离，
 * 避免 Nashorn (JDK15+ 移除) 与 OS 子进程 (PythonEngine) 的部署成本与隔离弱问题</p>
 * <p>支持语言：js（默认引入）、python（需在 pom 显式引入 org.graalvm.python:python，否则视为不可用）</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraalVmSandbox {

    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    private final ObjectMapper objectMapper;

    /** 单例 Engine：多 Context 共享编译缓存，降低首次创建开销 */
    private volatile Engine engine;

    /** 各语言插件是否可用（启动时探测，避免每次调用都 try-with-resources） */
    private volatile boolean jsAvailable;
    private volatile boolean pythonAvailable;

    /**
     * 启动时初始化 Engine 并探测可用语言
     */
    @PostConstruct
    public void init() {
        try {
            Engine.Builder builder = Engine.newBuilder()
                    .option("engine.WarnInterpreterOnly", "false");
            engine = builder.build();
            // 探测语言：getLanguages().containsKey 在 Engine 已加载该语言插件时返回 true
            jsAvailable = engine.getLanguages().containsKey("js");
            pythonAvailable = engine.getLanguages().containsKey("python");
            log.info("[GraalVmSandbox] 初始化完成: js={}, python={}", jsAvailable, pythonAvailable);
        } catch (Throwable e) {
            // NoClassDefFoundError 等兼容性异常：GraalVM 依赖未引入时降级到 Nashorn/subprocess
            log.warn("[GraalVmSandbox] GraalVM Engine 初始化失败，沙盒降级: {}", e.getMessage());
            engine = null;
            jsAvailable = false;
            pythonAvailable = false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isLanguageAvailable(String language) {
        if (engine == null) {
            return false;
        }
        return switch (language) {
            case "js" -> jsAvailable;
            case "python" -> pythonAvailable;
            default -> false;
        };
    }

    /**
     * 在 GraalVM 沙盒内执行代码
     *
     * @param language 语言标识（js / python）
     * @param code     代码内容
     * @param params   注入到脚本的参数（脚本内可通过 params 变量访问）
     * @param timeoutMs 执行超时（毫秒），<=0 用默认值
     * @return 执行结果
     */
    public CodeExecResultDTO execute(String language, String code, Map<String, Object> params, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        long start = System.currentTimeMillis();
        String lang = normalizeLanguage(language);

        if (engine == null || !isLanguageAvailable(lang)) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("GraalVM " + lang + " 语言未加载")
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language(lang)
                    .build();
        }

        // 捕获 stdout，沙盒内 print/echo 通过此 OutputStream 输出
        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

        // 1. 异步执行，外层用 CompletableFuture 实现超时（Context.cancel 会中断执行）
        try (Context ctx = Context.newBuilder(lang)
                .engine(engine)
                .allowExperimentalOptions(true)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false)
                .out(stdoutBuf)
                .err(stderrBuf)
                .build()) {

            // 2. 注入 params：先序列化为 JSON，再让 guest 语言解析为 guest 原生对象
            //    （HostAccess.NONE 下无法直接 putMember(hostMap) 透传）
            Value bindings = ctx.getBindings(lang);
            String paramsJson = toJson(params != null ? params : Map.of());
            String bootstrapping = buildParamsInjector(lang, paramsJson);
            ctx.eval(lang, bootstrapping);

            // 3. 执行（带超时）
            CompletableFuture<Value> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ctx.eval(lang, code);
                } catch (PolyglotException e) {
                    throw new RuntimeException(e);
                }
            });

            Value result;
            try {
                result = future.orTimeout(timeout, TimeUnit.MILLISECONDS).join();
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TimeoutException) {
                    ctx.close(true); // cancel: 强行中断正在执行的脚本
                    return CodeExecResultDTO.builder()
                            .success(false)
                            .error("代码执行超时（" + timeout + "ms），请检查是否存在死循环")
                            .elapsedMs(timeout)
                            .language(lang)
                            .build();
                }
                Throwable root = cause != null ? cause : e;
                // 解包 PolyglotException
                if (root.getCause() instanceof PolyglotException pe) {
                    return failureResult(pe, stdoutBuf, stderrBuf, lang, start);
                }
                if (root instanceof PolyglotException pe) {
                    return failureResult(pe, stdoutBuf, stderrBuf, lang, start);
                }
                return CodeExecResultDTO.builder()
                        .success(false)
                        .output(truncate(stdoutBuf.toString(StandardCharsets.UTF_8)))
                        .error("执行异常: " + sanitize(root.getMessage()))
                        .elapsedMs(System.currentTimeMillis() - start)
                        .language(lang)
                        .build();
            }

            // 4. 提取返回值：优先 main(params)，否则取 eval 结果
            String output = truncate(stdoutBuf.toString(StandardCharsets.UTF_8));
            Value bindingsAfter = ctx.getBindings(lang);
            String returnValue = extractReturnValue(ctx, bindingsAfter, result, lang);

            return CodeExecResultDTO.builder()
                    .success(true)
                    .output(output)
                    .returnValue(returnValue)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language(lang)
                    .build();
        }
    }

    /**
     * 失败结果组装：保留 stdout，stderr 作为错误信息
     */
    private CodeExecResultDTO failureResult(PolyglotException e, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, String lang, long start) {
        String output = truncate(stdout.toString(StandardCharsets.UTF_8));
        String errText = truncate(stderr.toString(StandardCharsets.UTF_8));
        String errMsg = e.isGuestException() ? e.getMessage() : sanitize(e.getMessage());
        if (errText != null && !errText.isBlank()) {
            errMsg = errMsg == null ? errText : errMsg + "\n" + errText;
        }
        return CodeExecResultDTO.builder()
                .success(false)
                .output(output)
                .error("执行错误: " + errMsg)
                .elapsedMs(System.currentTimeMillis() - start)
                .language(lang)
                .build();
    }

    /**
     * 提取脚本返回值：约定 main(params) 为入口，找不到则取 eval 末尾表达式的值
     */
    private String extractReturnValue(Context ctx, Value bindings, Value evalResult, String lang) {
        Value mainFn = bindings.getMember("main");
        Value result = evalResult;
        if (mainFn != null && mainFn.canExecute()) {
            try {
                result = mainFn.execute(bindings.getMember("params"));
            } catch (PolyglotException ignored) {
                // main 抛异常时保留 eval 结果
            }
        }
        if (result == null || result.isNull()) {
            return null;
        }
        return serializeValue(ctx, lang, result);
    }

    /**
     * 将 Polyglot Value 序列化为字符串：基本类型直接 toString，
     * 复杂类型调用沙盒内 guest 语言的 JSON 能力（HostAccess.NONE 下无法用 host ObjectMapper 转换 guest 对象）
     */
    private String serializeValue(Context ctx, String lang, Value value) {
        if (value.isString()) {
            return truncate(value.asString());
        }
        if (value.isNumber() || value.isBoolean()) {
            return truncate(value.toString());
        }
        // 复杂类型：调用沙盒内 JSON.stringify / json.dumps，避免 host-guest 互操作
        try {
            Value bindings = ctx.getBindings(lang);
            if ("js".equals(lang)) {
                Value json = bindings.getMember("JSON");
                if (json != null) {
                    Value str = json.getMember("stringify").execute(value);
                    return truncate(str.asString());
                }
            } else if ("python".equals(lang)) {
                // python.json 在 bootstrap 中已 import json；通过 eval 调 dumps
                Value dumps = ctx.eval("python", "json.dumps");
                Value str = dumps.execute(value);
                return truncate(str.asString());
            }
        } catch (Exception ignored) {
        }
        return truncate(String.valueOf(value));
    }

    private String normalizeLanguage(String language) {
        if (language == null) {
            return "js";
        }
        return switch (language.toLowerCase()) {
            case "js", "javascript", "ecmascript" -> "js";
            case "py", "python" -> "python";
            default -> language.toLowerCase();
        };
    }

    /**
     * host Map → JSON 字符串（host ObjectMapper 序列化，再交给 guest 解析）
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 构造 guest 语言自启动代码：解析 params JSON 并绑定到全局变量
     * <p>HostAccess.NONE 下无法用 putMember 直接透传 host Map，需 guest 语言自行解析 JSON 为 guest 原生对象</p>
     *
     * @param lang        语言（js / python）
     * @param paramsJson  params 的 JSON 字符串
     * @return guest 语言可执行的初始化代码
     */
    private String buildParamsInjector(String lang, String paramsJson) {
        String safe = escapeForGuestString(paramsJson);
        return switch (lang) {
            // JS：用 Function 包装避免字面量被解析为代码块
            case "js" -> "var params = (JSON.parse('" + safe + "'));";
            // Python：json 模块在 GraalPy 标准库中默认可用
            case "python" -> "import json\nparams = json.loads('" + safe + "')";
            default -> "";
        };
    }

    /**
     * JSON 文本嵌入 guest 字符串字面量时转义：' " \ 换行
     */
    private String escapeForGuestString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > MAX_OUTPUT_LENGTH
                ? text.substring(0, MAX_OUTPUT_LENGTH) + "... (截断)"
                : text;
    }

    private String sanitize(String message) {
        if (message == null) return "未知错误";
        return message.replaceAll("[A-Z]:\\\\[\\S]+", "<path>")
                .replaceAll("/[a-z]+/[a-z]+/[\\S]+", "<path>");
    }
}
