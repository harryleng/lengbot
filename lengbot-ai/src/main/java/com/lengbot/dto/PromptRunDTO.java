package com.lengbot.dto;

import lombok.Data;

/**
 * Prompt调试运行请求
 *
 * @author finch
 * @since 2026-05-27
 */
@Data
public class PromptRunDTO {

    private String promptKey;

    private String version;

    /** 直接传入模板内容（优先于promptKey+version） */
    private String template;

    private String variables;

    private String modelConfig;
}
