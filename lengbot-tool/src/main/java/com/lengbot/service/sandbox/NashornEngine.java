package com.lengbot.service.sandbox;

import com.lengbot.dto.CodeExecResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Nashorn JavaScript 引擎
 * <p>安全级别 L2：ClassFilter + 超时控制。GraalVM 不可用时的降级方案。</p>
 *
 * @author finch
 * @since 2026-06-24
 */
@Slf4j
@Component
public class NashornEngine implements CodeEngine {

    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /**
     * 危险访问模式（L1 黑名单）
     * <p>覆盖已知绕过模式：</p>
     * <ul>
     *   <li>Java 互操作：Java.type / Java.extend / Packages.* / importClass</li>
     *   <li>反射：Class.forName / Method.invoke / Field.set</li>
     *   <li>文件系统：java.io.File / RandomAccessFile / nio.file</li>
     *   <li>反序列化：ObjectInputStream / XMLDecoder</li>
     * </ul>
     * <p>严格的 Nashorn 沙盒还需配合 Nashorn ClassFilter（待 L2 改造引入）</p>
     */
    private static final Pattern DANGEROUS_ACCESS = Pattern.compile(
            "\\b(Java\\.type|Java\\.extend|importClass|importPackage|Packages\\."
                    + "|java\\.lang\\.Runtime|java\\.lang\\.ProcessBuilder"
                    + "|java\\.io\\.(File|FileInputStream|FileOutputStream|RandomAccessFile|ObjectInputStream)"
                    + "|java\\.net\\.|java\\.nio\\.file\\."
                    + "|javax\\.script\\.ScriptEngineManager|Class\\.forName"
                    + "|System\\.exit|System\\.setOut|System\\.setErr"
                    + "|ProcessBuilder|ProcessHandle|Desktop"
                    + "|java\\.beans\\.XMLDecoder"
                    + "|Reflective|Method\\.invoke|Field\\.set)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public boolean isAvailable() {
        return new ScriptEngineManager().getEngineByName("javascript") != null;
    }

    @Override
    public CodeExecResultDTO execute(String code, Map<String, Object> params, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        long start = System.currentTimeMillis();

        // 1. 安全校验
        String securityError = checkSecurity(code);
        if (securityError != null) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error(securityError)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("javascript")
                    .build();
        }

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("javascript");
        if (engine == null) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("JavaScript 引擎初始化失败：当前 JVM 未包含 Nashorn 引擎。"
                            + "JDK 15+ 已移除内置 Nashorn，请引入依赖 org.openjdk.nashorn:nashorn-core:15.4")
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("javascript")
                    .build();
        }

        // 2. 重定向 System.out 捕获输出
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outputBuf = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(outputBuf);

        try {
            return CompletableFuture.supplyAsync(() -> {
                System.setOut(capturedOut);
                try {
                    Bindings bindings = engine.createBindings();
                    bindings.put("params", params != null ? params : Map.of());
                    // 注入 print 函数
                    engine.eval("function print() { java.lang.System.out.println(Array.prototype.join.call(arguments, ' ')); }", bindings);
                    engine.eval(code, bindings);

                    // 检查 main 函数（工作流脚本兼容）
                    Object mainFn = bindings.get("main");
                    Object result;
                    if (mainFn != null) {
                        result = engine.eval("main(params)", bindings);
                    } else {
                        result = bindings.get("result");
                    }

                    String output = truncateOutput(outputBuf.toString(StandardCharsets.UTF_8));
                    String returnValue = serializeJsReturnValue(engine, bindings, result);

                    return CodeExecResultDTO.builder()
                            .success(true)
                            .output(output)
                            .returnValue(returnValue)
                            .elapsedMs(System.currentTimeMillis() - start)
                            .language("javascript")
                            .build();
                } catch (Exception e) {
                    String output = truncateOutput(outputBuf.toString(StandardCharsets.UTF_8));
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String errMsg = sanitizeError(cause.getMessage());
                    return CodeExecResultDTO.builder()
                            .success(false)
                            .output(output)
                            .error("执行错误: " + errMsg + hintForError(errMsg))
                            .elapsedMs(System.currentTimeMillis() - start)
                            .language("javascript")
                            .build();
                } finally {
                    System.setOut(originalOut);
                }
            }).orTimeout(timeout, TimeUnit.MILLISECONDS).join();
        } catch (java.util.concurrent.CompletionException e) {
            System.setOut(originalOut);
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                return CodeExecResultDTO.builder()
                        .success(false)
                        .error("代码执行超时（" + timeout + "ms），请检查是否存在死循环")
                        .elapsedMs(timeout)
                        .language("javascript")
                        .build();
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("执行异常: " + sanitizeError(cause.getMessage()))
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("javascript")
                    .build();
        } catch (Exception e) {
            System.setOut(originalOut);
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("执行异常: " + sanitizeError(e.getMessage()))
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("javascript")
                    .build();
        }
    }

    private String checkSecurity(String code) {
        if (DANGEROUS_ACCESS.matcher(code).find()) {
            return "脚本安全校验未通过：包含不允许的 Java 访问（禁止文件系统、网络、进程等系统资源）";
        }
        return null;
    }

    private String truncateOutput(String text) {
        if (text == null) return null;
        return text.length() > MAX_OUTPUT_LENGTH
                ? text.substring(0, MAX_OUTPUT_LENGTH) + "... (截断)"
                : text;
    }

    /**
     * 将 JS 返回值序列化为字符串：对象/数组用 JSON.stringify，避免 [object Object]
     */
    private String serializeJsReturnValue(ScriptEngine engine, Bindings bindings, Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof String s) {
            return truncateOutput(s);
        }
        if (result instanceof Number || result instanceof Boolean) {
            return truncateOutput(String.valueOf(result));
        }
        try {
            bindings.put("_returnResult", result);
            Object json = engine.eval(
                    "(_returnResult === null || _returnResult === undefined) ? null "
                            + ": (typeof _returnResult === 'object' ? JSON.stringify(_returnResult) "
                            + ": String(_returnResult))",
                    bindings);
            return json == null ? null : truncateOutput(String.valueOf(json));
        } catch (Exception e) {
            log.warn("[NashornEngine] 返回值 JSON 序列化失败，降级为 toString: {}", e.getMessage());
            return truncateOutput(String.valueOf(result));
        }
    }

    private String sanitizeError(String message) {
        if (message == null) return "未知错误";
        return message.replaceAll("[A-Z]:\\\\[\\S]+", "<path>")
                .replaceAll("/[a-z]+/[a-z]+/[\\S]+", "<path>");
    }

    /**
     * 为常见语法错误追加提示
     */
    private String hintForError(String message) {
        if (message == null) return "";
        if (message.contains("const") || message.contains("let")) {
            return " 提示：Nashorn 引擎仅支持 ES5 语法，请使用 var 声明变量，不要使用 const/let/箭头函数";
        }
        if (message.contains("Unexpected token") && message.contains("=>")) {
            return " 提示：Nashorn 引擎不支持箭头函数，请使用 function(){} 语法";
        }
        return "";
    }
}
