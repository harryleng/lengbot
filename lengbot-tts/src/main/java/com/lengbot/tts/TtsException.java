package com.lengbot.tts;

import com.lengbot.common.BizException;
import com.lengbot.enums.ErrorCode;

/**
 * TTS 合成异常。统一包装为业务异常，便于 Controller 统一返回。
 *
 * @author LengBot Team
 * @since 1.0.0
 */
public class TtsException extends BizException {

    public TtsException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }

    public TtsException(String message, Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR, message + " | " + cause.getMessage());
    }
}
