package com.lengbot.vo;
import com.lengbot.dto.*;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户个人配置响应
 *
 * @author finch
 * @since 2026-07-09
 */
@Data
@Schema(description = "用户个人配置")
public class UserPreferenceVO {

    @Schema(description = "是否启用长期记忆")
    private Boolean longMemoryEnabled;

    @Schema(description = "是否启用自动记忆抽取")
    private Boolean longMemoryAutoExtract;

    @Schema(description = "每轮最多注入记忆数量")
    private Integer longMemoryInjectLimit;

    @Schema(description = "记忆作用域：user/agent")
    private String longMemoryScope;
}
