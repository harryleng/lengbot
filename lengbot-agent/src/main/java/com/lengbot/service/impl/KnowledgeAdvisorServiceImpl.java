package com.lengbot.service.impl;

import com.lengbot.entity.Chunk;
import com.lengbot.entity.Document;
import com.lengbot.mapper.KnowledgeAdvisorMapper;
import com.lengbot.service.ChunkService;
import com.lengbot.service.DocumentService;
import com.lengbot.service.KnowledgeAdvisorService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.vo.KnowledgeAdvisorSummaryVO;
import com.lengbot.vo.LowRatedChunkVO;
import com.lengbot.vo.SleepingChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库 Advisor 服务实现
 * <p>聚合 message_feedback 反馈数据，结合 chunk 表生成调优建议</p>
 *
 * @author finch
 * @since 2026-07-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAdvisorServiceImpl implements KnowledgeAdvisorService {

    /** 默认休眠阈值天数 */
    private static final int DEFAULT_SLEEPING_DAYS = 14;
    /** 默认返回条数 */
    private static final int DEFAULT_LIMIT = 10;
    /** 内容预览长度上限 */
    private static final int PREVIEW_MAX_LENGTH = 200;

    private final KnowledgeAdvisorMapper knowledgeAdvisorMapper;
    private final KnowledgeService knowledgeService;
    private final ChunkService chunkService;
    private final DocumentService documentService;

    @Override
    public KnowledgeAdvisorSummaryVO getSummary(Long knowledgeId, int windowDays) {
        // 1. 权限校验：需为知识库成员（复用 KnowledgeService 校验逻辑）
        knowledgeService.getByIdWithPermission(knowledgeId);

        // 2. 窗口天数兜底
        int days = windowDays > 0 ? windowDays : DEFAULT_SLEEPING_DAYS;
        String knowledgeIdStr = String.valueOf(knowledgeId);

        // 3. 反馈概览：单行聚合（无引用记录时 Map 为 null/空，需兜底）
        Map<String, Object> row = knowledgeAdvisorMapper.summaryFeedback(knowledgeIdStr);
        long totalReferences = longVal(row, "totalreferences");
        long totalLikes = longVal(row, "totallikes");
        long totalFeedback = longVal(row, "totalfeedback");
        long referencedChunkCount = longVal(row, "referencedchunkcount");

        // 4. 点赞率 = like / (like + dislike)
        //    totalFeedback 含 like 和 dislike，单独取 dislike 不便——用 totalFeedback - totalLikes 推导
        long totalDislikes = Math.max(totalFeedback - totalLikes, 0);
        double likeRate = totalFeedback > 0
                ? (double) totalLikes / totalFeedback
                : 0.0;

        // 5. 休眠分块数（独立查询）
        long sleepingChunkCount = knowledgeAdvisorMapper.countSleepingChunks(knowledgeIdStr, days);

        // 6. 组装 VO
        KnowledgeAdvisorSummaryVO vo = new KnowledgeAdvisorSummaryVO();
        vo.setKnowledgeId(knowledgeId);
        vo.setTotalReferences(totalReferences);
        vo.setTotalLikes(totalLikes);
        vo.setTotalDislikes(totalDislikes);
        vo.setLikeRate(likeRate);
        vo.setReferencedChunkCount(referencedChunkCount);
        vo.setSleepingChunkCount(sleepingChunkCount);
        vo.setWindowDays(days);
        return vo;
    }

    @Override
    public List<LowRatedChunkVO> getLowRatedChunks(Long knowledgeId, int limit) {
        // 1. 权限校验
        knowledgeService.getByIdWithPermission(knowledgeId);

        // 2. 条数兜底
        int maxLimit = limit > 0 ? Math.min(limit, 50) : DEFAULT_LIMIT;
        String knowledgeIdStr = String.valueOf(knowledgeId);

        // 3. 聚合查询：返回 chunkId + 计数
        List<Map<String, Object>> rows = knowledgeAdvisorMapper.lowRatedChunks(knowledgeIdStr, maxLimit);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 4. 批量补全分块信息（避免 N+1）
        List<Long> chunkIds = rows.stream().map(r -> longVal(r, "chunkid")).toList();
        Map<Long, Chunk> chunkMap = chunkService.listByIds(chunkIds).stream()
                .collect(Collectors.toMap(Chunk::getId, c -> c));

        // 5. 批量补全文档名称
        Set<Long> documentIds = chunkMap.values().stream()
                .map(Chunk::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> docNameMap = documentIds.isEmpty() ? Map.of()
                : documentService.listByIds(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Document::getName));

        // 6. 组装 VO
        List<LowRatedChunkVO> results = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long chunkId = longVal(row, "chunkid");
            Chunk chunk = chunkMap.get(chunkId);
            if (chunk == null) {
                // 分块已被删除，跳过
                continue;
            }
            long likeCount = longVal(row, "likecount");
            long dislikeCount = longVal(row, "dislikecount");
            long referenceCount = longVal(row, "referencecount");

            LowRatedChunkVO vo = new LowRatedChunkVO();
            vo.setChunkId(chunkId);
            vo.setDocumentId(chunk.getDocumentId());
            vo.setDocumentName(docNameMap.getOrDefault(chunk.getDocumentId(), ""));
            vo.setContentPreview(preview(chunk.getContent()));
            vo.setLikeCount(likeCount);
            vo.setDislikeCount(dislikeCount);
            // 点踩率：dislike / (like + dislike)
            long feedbackTotal = likeCount + dislikeCount;
            vo.setDislikeRate(feedbackTotal > 0 ? (double) dislikeCount / feedbackTotal : 0.0);
            vo.setReferenceCount(referenceCount);
            vo.setLastReferencedAt(toLocalDateTime(row.get("lastreferencedat")));
            results.add(vo);
        }
        return results;
    }

    @Override
    public List<SleepingChunkVO> getSleepingChunks(Long knowledgeId, int days, int limit) {
        // 1. 权限校验
        knowledgeService.getByIdWithPermission(knowledgeId);

        // 2. 参数兜底
        int sleepDays = days > 0 ? days : DEFAULT_SLEEPING_DAYS;
        int maxLimit = limit > 0 ? Math.min(limit, 50) : DEFAULT_LIMIT;
        String knowledgeIdStr = String.valueOf(knowledgeId);

        // 3. 查询休眠分块（已含 chunk 信息，无需再批量查 chunk 表）
        List<Map<String, Object>> rows = knowledgeAdvisorMapper.sleepingChunks(knowledgeIdStr, sleepDays, maxLimit);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 4. 批量补全文档名称
        Set<Long> documentIds = rows.stream()
                .map(r -> longVal(r, "documentid"))
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> docNameMap = documentIds.isEmpty() ? Map.of()
                : documentService.listByIds(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Document::getName));

        // 5. 组装 VO + 计算休眠天数
        LocalDateTime now = LocalDateTime.now();
        List<SleepingChunkVO> results = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SleepingChunkVO vo = new SleepingChunkVO();
            vo.setChunkId(longVal(row, "chunkid"));
            vo.setDocumentId(longVal(row, "documentid"));
            vo.setDocumentName(docNameMap.getOrDefault(vo.getDocumentId(), ""));
            String content = (String) row.get("content");
            vo.setContentPreview(preview(content));
            vo.setChunkCreateTime(toLocalDateTime(row.get("chunkcreatetime")));
            LocalDateTime lastRef = toLocalDateTime(row.get("lastreferencedat"));
            vo.setLastReferencedAt(lastRef);
            vo.setReferenceCount(longVal(row, "referencecount"));
            // 休眠天数：从未引用 -> 自创建起的天数；已引用 -> 自最近引用起的天数
            LocalDateTime baseline = lastRef != null ? lastRef : vo.getChunkCreateTime();
            vo.setSleepingDays(baseline != null ? ChronoUnit.DAYS.between(baseline, now) : 0);
            results.add(vo);
        }
        return results;
    }

    /**
     * 从 Map 中安全取 Long 值（兼容 Number/字符串/null）
     */
    private long longVal(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object val = row.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 数据库时间字段转 LocalDateTime（兼容 Timestamp/LocalDateTime）
     */
    private LocalDateTime toLocalDateTime(Object val) {
        if (val instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (val instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (val instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime();
        }
        return null;
    }

    /**
     * 截取内容预览，超过 200 字符加省略号
     */
    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= PREVIEW_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_LENGTH) + "...";
    }
}
