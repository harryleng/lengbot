package com.lengbot.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.dto.CodeExecResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Python 代码执行引擎（ProcessBuilder 子进程方式）
 * <p>安全级别 L4：OS 进程隔离，天然沙盒。依赖宿主机安装 Python 3。</p>
 *
 * @author lw
 * @since 2026-06-24
 */
@Slf4j
@Component
public class PythonEngine implements CodeEngine {

    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final String RESULT_START_MARKER = "__SANDBOX_RESULT_START__";
    private static final String RESULT_END_MARKER = "__SANDBOX_RESULT_END__";

    /**
     * 危险 import 黑名单（L1 快速过滤，配合 -E -S 启动参数 + 工作目录隔离形成纵深防御）
     * <p>覆盖已知绕过模式：</p>
     * <ul>
     *   <li>直接 import：os/subprocess/socket/http 等</li>
     *   <li>动态 import：__import__("os")、importlib.import_module("os")</li>
     *   <li>builtins 提取：globals()["__builtins__"].__import__(...)</li>
     *   <li>反射绕过：getattr(__builtins__, "__import__")(...)</li>
     * </ul>
     */
    private static final Pattern BLOCKED_IMPORTS = Pattern.compile(
            "\\b(import\\s+(os|subprocess|shutil|socket|http|urllib|ftplib|smtplib|ctypes|sys|signal|multiprocessing|threading|importlib|builtins|marshal|imp|pty|platform|asyncio|telnetlib|paramiko)"
                    + "|from\\s+(os|subprocess|shutil|socket|http|urllib|ftplib|smtplib|ctypes|sys|signal|multiprocessing|threading|importlib|builtins|marshal|imp|pty|platform|asyncio|telnetlib|paramiko)\\s+import)"
                    + "|__import__\\s*\\("
                    + "|importlib\\.(import_module|__import__|find_loader|reload)"
                    + "|__builtins__"
                    + "|globals\\(\\)\\s*\\["
                    + "|getattr\\s*\\(\\s*['\"]__import__['\"]"
                    + "|exec\\s*\\("
                    + "|eval\\s*\\("
                    + "|compile\\s*\\("
                    + "|open\\s*\\([^)]*['\"]w", Pattern.CASE_INSENSITIVE);

    /** Python 3 解释器候选路径 */
    private static final String[] PYTHON_CANDIDATES = {"python3", "python"};

    /** 容器中无 ObjectMapper Bean 时的兜底实例（仅用于序列化 params） */
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    private final ObjectMapper objectMapper;

    public PythonEngine(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : FALLBACK_MAPPER;
    }

    @Override
    public String language() {
        return "python";
    }

    @Override
    public boolean isAvailable() {
        for (String cmd : PYTHON_CANDIDATES) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int exitCode = p.waitFor();
                if (exitCode == 0 && output.contains("Python 3")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public CodeExecResultDTO execute(String code, Map<String, Object> params, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        long start = System.currentTimeMillis();

        // 1. 安全校验：危险 import 拦截
        String securityError = checkSecurity(code);
        if (securityError != null) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error(securityError)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("python")
                    .build();
        }

        // 2. 包装用户代码
        String wrappedCode = wrapCode(code, params);

        // 3. 查找 Python 解释器
        String pythonCmd = findPython();
        if (pythonCmd == null) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("Python 执行环境不可用：服务器未安装 Python 3.8+。"
                            + "请在服务器上安装 Python 3（apt install python3 / yum install python3），"
                            + "并确保 python3 或 python 命令可用")
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("python")
                    .build();
        }

        // 4. 创建隔离的临时工作目录 + 写入脚本
        // 工作目录隔离：子进程在独立空目录内运行，避免读取 JVM 工作目录文件
        File tempFile;
        File workDir;
        try {
            workDir = Files.createTempDirectory("sandbox_workdir_").toFile();
            workDir.deleteOnExit();
            tempFile = new File(workDir, "script.py");
            tempFile.deleteOnExit();
            Files.writeString(tempFile.toPath(), wrappedCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("创建临时文件失败: " + e.getMessage())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("python")
                    .build();
        }

        try {
            // 5. 启动子进程
            // -S：禁用 site 模块（屏蔽 site-packages 中的危险模块）
            // -E：禁用 PYTHON* 环境变量（防止 PYTHONSTARTUP 等注入）
            // -s：禁用用户 site-packages
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "-X", "utf8", "-S", "-E", "-s", tempFile.getAbsolutePath());
            pb.redirectErrorStream(false);
            pb.directory(workDir);
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONUNBUFFERED", "1");

            Process process = pb.start();

            // 6. 读取 stdout 和 stderr
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            // 7. 等待执行完成（带超时）
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CodeExecResultDTO.builder()
                        .success(false)
                        .error("Python 执行超时（" + timeout + "ms），请检查是否存在死循环")
                        .elapsedMs(timeout)
                        .language("python")
                        .build();
            }

            int exitCode = process.exitValue();
            String output = truncateOutput(stripReturnValueBlock(stdout));
            String errorOutput = truncateOutput(stderr);

            if (exitCode == 0) {
                // 解析返回值（最后一行 stdout 为返回值标记）
                String returnValue = parseReturnValue(stdout);
                return CodeExecResultDTO.builder()
                        .success(true)
                        .output(output)
                        .returnValue(returnValue)
                        .elapsedMs(System.currentTimeMillis() - start)
                        .language("python")
                        .build();
            } else {
                return CodeExecResultDTO.builder()
                        .success(false)
                        .output(output)
                        .error("Python 执行失败 (exit=" + exitCode + "): " + sanitizeError(errorOutput))
                        .elapsedMs(System.currentTimeMillis() - start)
                        .language("python")
                        .build();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("执行被中断")
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("python")
                    .build();
        } catch (Exception e) {
            return CodeExecResultDTO.builder()
                    .success(false)
                    .error("执行异常: " + sanitizeError(e.getMessage()))
                    .elapsedMs(System.currentTimeMillis() - start)
                    .language("python")
                    .build();
        } finally {
            tempFile.delete();
            // 清理工作目录（包含可能产生的临时输出文件）
            File[] leftovers = workDir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }
                }
            }
            if (!workDir.delete()) {
                workDir.deleteOnExit();
            }
        }
    }

    /**
     * 将用户代码包装为可执行的 Python 脚本
     * <p>注入 params 变量，捕获 main() 返回值，通过标记输出。</p>
     * <p><b>params 注入方式</b>：经 {@link #encodeParamsLiteral(Map)} 编码为 Base64 字面量，
     * 而非拼接原始 JSON 字符串——详见该方法的安全说明。</p>
     */
    private String wrapCode(String code, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("import json, sys, base64\n");
        sb.append("if hasattr(sys.stdout, 'reconfigure'):\n");
        sb.append("    sys.stdout.reconfigure(encoding='utf-8')\n");
        sb.append("    sys.stderr.reconfigure(encoding='utf-8')\n");

        // 注入 params（Base64 字面量，不可闭合、不可注入）
        sb.append("params = ").append(encodeParamsLiteral(params)).append("\n");

        sb.append("\n");
        sb.append(code.strip());
        sb.append("\n\n");

        // 调用 main() 并捕获返回值
        sb.append("try:\n");
        sb.append("    _result = main()\n");
        sb.append("    if _result is not None:\n");
        sb.append("        print('" + RESULT_START_MARKER + "')\n");
        sb.append("        if isinstance(_result, (dict, list)):\n");
        sb.append("            print(json.dumps(_result, ensure_ascii=False))\n");
        sb.append("        else:\n");
        sb.append("            print(_result)\n");
        sb.append("        print('" + RESULT_END_MARKER + "')\n");
        sb.append("except NameError:\n");
        sb.append("    pass\n");
        sb.append("except Exception as e:\n");
        sb.append("    print(f'Error: {e}', file=sys.stderr)\n");
        sb.append("    sys.exit(1)\n");

        return sb.toString();
    }

    /**
     * 解析 main() 的返回值（从标记中提取）
     */
    private String parseReturnValue(String stdout) {
        if (stdout == null) return null;
        int startIdx = stdout.indexOf(RESULT_START_MARKER);
        int endIdx = stdout.indexOf(RESULT_END_MARKER);
        if (startIdx >= 0 && endIdx > startIdx) {
            String value = stdout.substring(startIdx + RESULT_START_MARKER.length(), endIdx).trim();
            return truncateOutput(value);
        }
        return null;
    }

    /**
     * 从标准输出中剔除返回值标记块，避免 output 与 returnValue 重复展示。
     */
    private String stripReturnValueBlock(String stdout) {
        if (stdout == null) return null;
        int startIdx = stdout.indexOf(RESULT_START_MARKER);
        int endIdx = stdout.indexOf(RESULT_END_MARKER);
        if (startIdx < 0 || endIdx <= startIdx) {
            return stdout;
        }
        int blockEndIdx = endIdx + RESULT_END_MARKER.length();
        String before = stdout.substring(0, startIdx).stripTrailing();
        String after = stdout.substring(blockEndIdx).stripLeading();
        if (before.isEmpty()) return after;
        if (after.isEmpty()) return before;
        return before + System.lineSeparator() + after;
    }

    private String findPython() {
        for (String cmd : PYTHON_CANDIDATES) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int exitCode = p.waitFor();
                if (exitCode == 0 && output.contains("Python 3")) {
                    return cmd;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String checkSecurity(String code) {
        if (BLOCKED_IMPORTS.matcher(code).find()) {
            return "Python 安全校验未通过：包含不允许的模块（禁止 os/subprocess/socket/http/sys 等系统模块）";
        }
        return null;
    }

    private String readStream(InputStream is) {
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 把 params 变成一段「不可能被注入」的 Python 表达式。
     *
     * <p>做法：{@code params → JSON 字符串 → Base64 → json.loads(base64.b64decode("..."))}。</p>
     *
     * <p><b>为什么必须这样</b>：历史实现是 {@code json.loads('...')} 直接拼接，由转义函数负责挡住
     * 参数值里的引号。而转义函数只处理了 {@code \ " \n \r \t}，<b>漏掉了单引号</b>，
     * 偏偏外层用的就是单引号——于是参数值里一个 {@code '} 就能闭合字符串字面量，
     * 后面接任意 Python 表达式。params 来自 LLM 生成的工具参数（不可信输入），
     * 这构成了一条 prompt injection → 子进程 RCE 的完整链路。</p>
     *
     * <p><b>为什么 Base64 是根治</b>：Base64 字符集只有 {@code [A-Za-z0-9+/=]}，
     * 不含引号、反斜杠、换行、分号——无论参数值里塞什么字符，都只会被编码成字母数字，
     * 在语法上永远无法跳出双引号。安全性由字符集保证，而不依赖转义逻辑的正确性。
     * （转义是「黑名单思维」，漏一个字符就破防；编码是「白名单思维」，字符集天然闭合。）</p>
     *
     * <p>序列化用 Jackson 而非手写拼接：手写版对嵌套 Map/List 会退化成 {@code String.valueOf}
     * 的 {@code {a=1}} 形式，那不是合法 JSON，Base64 之后照样解析失败。</p>
     */
    private String encodeParamsLiteral(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            String json = objectMapper.writeValueAsString(params);
            String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return "json.loads(base64.b64decode(\"" + b64 + "\").decode('utf-8'))";
        } catch (Exception e) {
            // 序列化失败不该让执行继续带着畸形参数跑，退化为无参数并留下告警
            log.warn("[PythonEngine] params 序列化失败，本次以空参数执行", e);
            return "{}";
        }
    }

    private String truncateOutput(String text) {
        if (text == null) return null;
        return text.length() > MAX_OUTPUT_LENGTH
                ? text.substring(0, MAX_OUTPUT_LENGTH) + "... (截断)"
                : text;
    }

    private String sanitizeError(String message) {
        if (message == null) return "未知错误";
        return message.replaceAll("[A-Z]:\\\\[\\S]+", "<path>")
                .replaceAll("/[a-z]+/[a-z]+/[\\S]+", "<path>");
    }
}
