package com.lengbot.model.chunking;

import lombok.Data;

/**
 * 分块结果块（支持父子分块）
 * <p>普通策略返回单级块（parentContent=null）；父子策略返回子块（parentContent=父块全文）。</p>
 *
 * @author lw
 * @since 2026-10-09
 */
@Data
public class ChunkBlock {

    /** 块文本（检索/向量化单元） */
    private String content;

    /** 父块全文缓存（父子分块模式下非 null；单级分块为 null） */
    private String parentContent;

    /** 是否为父块（轻量方案父块不入库，恒为 false） */
    private boolean isParent;

    public ChunkBlock() {
    }

    public ChunkBlock(String content, String parentContent, boolean isParent) {
        this.content = content;
        this.parentContent = parentContent;
        this.isParent = isParent;
    }

    /** 单级叶子块（无父块） */
    public static ChunkBlock leaf(String content) {
        return new ChunkBlock(content, null, false);
    }

    /** 父子分块中的子块（携带父块全文） */
    public static ChunkBlock child(String content, String parentContent) {
        return new ChunkBlock(content, parentContent, false);
    }
}
