package com.lengbot.common.task;

/**
 * 致命任务异常
 * <p>用于描述确定性失败（如参数非法、文件不存在、业务规则不通过、模型返回致命错误等），
 * Worker 不会重试，直接 markFailed 并写入死信 Stream 留待人工介入。
 *
 * @author finch
 * @since 2026-07-18
 */
public class FatalTaskException extends TaskException {

    public FatalTaskException(String message) {
        super(message);
    }

    public FatalTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
