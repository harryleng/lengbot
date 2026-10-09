package com.lengbot.model.chunking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块策略（对标 Dify 父子模式）
 * <p>先按分隔符合并为大父块（~chunkTokenNum token），再对每个父块切为小儿子块（~chunkTokenNum/3 token + 重叠）；
 * 每个子块携带其父块全文（parentContent）。入库时不单独存父块，仅把父块全文缓存进子块，
 * 检索命中子块时用 parentContent 回填父块上下文，兼顾“子块精准召回”与“父块完整上下文”。</p>
 *
 * @author lw
 * @since 2026-10-09
 */
@Slf4j
@Component
public class ParentChildChunkStrategy implements ChunkStrategy {

    private static final int MIN_CHILD_TOKEN_NUM = 80;
    private static final int DEFAULT_PARENT_TOKEN_NUM = 600;
    private static final int DEFAULT_CHILD_OVERLAP_PERCENT = 25;
    private static final int MAX_OVERLAP_TOKENS = 200;

    @Override
    public String getType() {
        return "parent-child";
    }

    /** 兼容旧调用（预览/测试分块）：返回子块文本列表 */
    @Override
    public List<String> split(String content, ChunkParams params) {
        return splitStructured(content, params).stream().map(ChunkBlock::getContent).toList();
    }

    /** 结构化分块：返回携带父块全文的子块列表 */
    @Override
    public List<ChunkBlock> splitStructured(String content, ChunkParams params) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int parentTokenNum = params.getChunkTokenNum() > 0 ? params.getChunkTokenNum() : DEFAULT_PARENT_TOKEN_NUM;
        int childTokenNum = Math.max(MIN_CHILD_TOKEN_NUM, parentTokenNum / 3);
        int childOverlap = params.getOverlappedPercent() > 0
                ? Math.max(20, parentTokenNum * params.getOverlappedPercent() / 100)
                : DEFAULT_CHILD_OVERLAP_PERCENT;

        // 1. 父块：大块切分（不重叠）
        ChunkParams parentParams = new ChunkParams();
        parentParams.setDelimiter(params.getDelimiter());
        parentParams.setChunkTokenNum(parentTokenNum);
        parentParams.setOverlappedPercent(0);
        List<String> parents = new GeneralChunkStrategy().split(content, parentParams);

        // 2. 每个父块切成子块（带重叠），缓存父块全文
        ChunkParams childParams = new ChunkParams();
        childParams.setDelimiter(params.getDelimiter());
        childParams.setChunkTokenNum(childTokenNum);
        childParams.setOverlappedPercent(0);
        List<ChunkBlock> blocks = new ArrayList<>();
        for (String parent : parents) {
            List<String> children = new GeneralChunkStrategy().split(parent, childParams);
            children = addOverlap(children, childOverlap, params.getDelimiter());
            for (String child : children) {
                blocks.add(ChunkBlock.child(child, parent));
            }
        }
        log.info("[父子分块] 父块数={}, 子块数={}", parents.size(), blocks.size());
        return blocks;
    }

    /** 为相邻子块添加尾部重叠（避免句子被切断，复用 GeneralChunkStrategy 思路） */
    private List<String> addOverlap(List<String> chunks, int overlapPercent, String delimiter) {
        if (overlapPercent <= 0 || chunks.size() <= 1) {
            return chunks;
        }
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String curr = chunks.get(i);
            int prevTokens = TokenUtil.countTokens(prev);
            int overlapTokens = Math.min(prevTokens * overlapPercent / 100, MAX_OVERLAP_TOKENS);
            if (overlapTokens > 0) {
                String overlapText = tailTokens(prev, overlapTokens, delimiter);
                result.add(overlapText + delimiter + curr);
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    private String tailTokens(String text, int tokenCount, String delimiter) {
        if (text == null || text.isEmpty() || tokenCount <= 0) {
            return "";
        }
        String[] parts = text.split(delimiter, -1);
        List<String> tail = new ArrayList<>();
        int accumulated = 0;
        for (int i = parts.length - 1; i >= 0; i--) {
            int tokens = TokenUtil.countTokens(parts[i]);
            if (accumulated + tokens > tokenCount) {
                if (tail.isEmpty()) {
                    tail.addFirst(TokenUtil.tailTokens(parts[i], tokenCount));
                }
                break;
            }
            tail.addFirst(parts[i]);
            accumulated += tokens;
        }
        return String.join(delimiter, tail);
    }
}
