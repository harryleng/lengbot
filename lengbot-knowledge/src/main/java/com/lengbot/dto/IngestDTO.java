package com.lengbot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

import com.lengbot.util.TextCleanRule;

/**
 * 文档入库请求DTO
 *
 * @author lw
 * @since 2026-05-20
 */
@Data
public class IngestDTO {

    @NotBlank(message = "分块策略不能为空")
    private String chunkStrategy;

    @NotNull(message = "分块大小不能为空")
    @Min(value = 100, message = "分块大小最小100")
    @Max(value = 2000, message = "分块大小最大2000")
    private Integer chunkSize;

    @NotNull(message = "重叠百分比不能为空")
    @Min(value = 0, message = "重叠百分比最小0")
    @Max(value = 99, message = "重叠百分比最大99")
    private Integer chunkOverlap;

    private String chunkDelimiter;

    /** 是否折叠连续空白（Dify remove_extra_spaces 等价）：连续空格/换行/制表符折叠为单个空格，默认关闭 */
    private Boolean collapseWhitespace;

    /** 文本清洗规则：入库分块前对文档内容做正则替换/删除（replacement 为空表示删除，支持 $1 捕获组） */
    private List<TextCleanRule> textCleanRules;
}
