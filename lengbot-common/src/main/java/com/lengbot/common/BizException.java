package com.lengbot.common;

import com.lengbot.enums.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常
 *
 * @author lw
 * @since 2026-05-19
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    private final HttpStatus httpStatus;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = HttpStatus.BAD_REQUEST;
    }

    /**
     * 通过错误码枚举构造异常
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 通过错误码枚举 + 自定义消息构造异常。
     * <p>
     * 必须存在本重载：否则 {@code new BizException(ErrorCode.X, "具体原因")} 会被解析为下方的
     * varargs 重载 {@code (ErrorCode, Object...)}，进而执行
     * {@code String.format(errorCode.getMessage(), "具体原因")} —— 由于枚举默认文案中无占位符，
     * 自定义消息会被整体吞掉，前端永远只看到「服务器内部错误」之类。
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 通过错误码枚造异常（支持多参数格式化消息）
     */
    public BizException(ErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 通过错误码枚举构造异常（保留原始异常）
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }
}
