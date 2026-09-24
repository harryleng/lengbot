package com.lengbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码执行结果
 *
 * @author lw
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeExecResultDTO {

    /** 是否执行成功 */
    private boolean success;

    /** stdout 输出 */
    private String output;

    /** 返回值（toString） */
    private String returnValue;

    /** 错误信息 */
    private String error;

    /** 执行耗时（毫秒） */
    private long elapsedMs;

    /** 实际使用的语言 */
    private String language;

    /** 执行产生的文件产物（Python 等语言生成的二进制/文件，Base64 编码）。无产物时为 null */
    private List<CodeArtifactDTO> artifacts;
}
