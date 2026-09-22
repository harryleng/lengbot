package com.lengbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码执行产生的文件产物（如 Python 通过 python-pptx 生成的 pptx）
 * <p>引擎在执行完成后、清理临时工作目录前，将脚本生成的文件读为字节并以 Base64 回传，
 * 由工具层（ExecuteCodeTool）解码后写入会话 outputs/ 工作区交付给用户。</p>
 *
 * @author lw
 * @since 2026-09-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeArtifactDTO {

    /** 产物文件名，如 report.pptx */
    private String name;

    /** 文件字节的 Base64 编码 */
    private String base64;

    /** 文件字节数 */
    private long size;
}
