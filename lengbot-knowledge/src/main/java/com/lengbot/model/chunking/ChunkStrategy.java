package com.lengbot.model.chunking;

import java.util.List;

/**
 * 分块策略接口
 *
 * @author lw
 * @since 2026-05-20
 */
public interface ChunkStrategy {

    /**
     * 策略类型标识
     *
     * @return 类型名称（general / book / separator）
     */
    String getType();

    /**
     * 将文本内容按策略拆分为多个分块
     *
     * @param content 原始文本内容
     * @param params  分块参数
     * @return 分块后的内容列表
     */
    List<String> split(String content, ChunkParams params);

    /**
     * 结构化分块（支持父子分块）。
     * 默认实现将 split 结果包装为单级叶子块；父子策略重写此方法返回携带父块全文的子块。
     */
    default List<ChunkBlock> splitStructured(String content, ChunkParams params) {
        return split(content, params).stream().map(ChunkBlock::leaf).toList();
    }
}
