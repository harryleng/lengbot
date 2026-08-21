package com.lengbot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 用户个人配置更新请求
 *
 * @author lw
 * @since 2026-07-09
 */
@Data
public class UserPreferenceUpdateDTO {

    private Boolean longMemoryEnabled;

    private Boolean longMemoryAutoExtract;

    @Min(value = 1, message = "记忆注入数量最小为1")
    @Max(value = 15, message = "记忆注入数量最大为15")
    private Integer longMemoryInjectLimit;

    private String longMemoryScope;
}
