package com.lengbot.util;

import lombok.Data;
import java.io.Serializable;

/**
 * 文本清洗规则：入库分块前对文档内容做正则替换/删除。
 * <p>regex 为空或非法时该规则被忽略；replacement 为空字符串表示删除匹配到的文本，
 * replacement 支持 $1 等捕获组反向引用（Java Matcher.replaceAll 语义）。</p>
 *
 * @author lw
 * @since 2026-10-12
 */
@Data
public class TextCleanRule implements Serializable {

    /** 正则表达式（Java Pattern 语法） */
    private String regex;

    /** 替换内容；空字符串表示删除匹配文本。支持 $1 等捕获组反向引用 */
    private String replacement = "";
}
