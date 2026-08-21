package com.lengbot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 知识库 Advisor 聚合 Mapper
 * <p>从 message.metadata.ragReferences 展开分块引用，关联 message_feedback 聚合反馈数据。
 * ragReferences 中 chunkId/knowledgeId 因前端 ToStringSerializer 序列化为字符串，
 * SQL 中以 ->> 取文本后再 ::bigint 转换</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Mapper
public interface KnowledgeAdvisorMapper {

    /**
     * 知识库反馈概览：单条聚合行
     * <p>引用数 = 出现该知识库分块的"消息数"；like/dislike = 这些消息的反馈数</p>
     *
     * @param knowledgeId 知识库ID
     * @return 单行 Map：totalReferences / totalLikes / totalDislikes / referencedChunkCount
     */
    @Select("""
            SELECT
                COUNT(DISTINCT m.id)                                                       AS totalReferences,
                COUNT(DISTINCT mf.id) FILTER (WHERE mf.rating = 'like')                   AS totalLikes,
                COUNT(DISTINCT mf.id) FILTER (WHERE mf.id IS NOT NULL)                    AS totalFeedback,
                COUNT(DISTINCT ref->>'chunkId')                                            AS referencedChunkCount
            FROM message m
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(m.metadata->'ragReferences') = 'array'
                     THEN m.metadata->'ragReferences' ELSE '[]'::jsonb END
            ) AS ref
            LEFT JOIN message_feedback mf ON mf.message_id = m.id
            WHERE m.metadata->'ragReferences' @> jsonb_build_array(jsonb_build_object('knowledgeId', #{knowledgeId}))
              AND ref->>'knowledgeId' = #{knowledgeId}
            """)
    Map<String, Object> summaryFeedback(@Param("knowledgeId") String knowledgeId);

    /**
     * 低分分块列表：按点踩数倒序
     *
     * @param knowledgeId 知识库ID
     * @param limit       最大返回数
     * @return 每行 Map：chunkId / likeCount / dislikeCount / referenceCount / lastReferencedAt
     */
    @Select("""
            SELECT
                (ref->>'chunkId')::bigint                                                  AS chunkId,
                COUNT(DISTINCT m.id)                                                       AS referenceCount,
                COUNT(DISTINCT mf.id) FILTER (WHERE mf.rating = 'like')                   AS likeCount,
                COUNT(DISTINCT mf.id) FILTER (WHERE mf.rating = 'dislike')                AS dislikeCount,
                MAX(m.create_time)                                                         AS lastReferencedAt
            FROM message m
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(m.metadata->'ragReferences') = 'array'
                     THEN m.metadata->'ragReferences' ELSE '[]'::jsonb END
            ) AS ref
            LEFT JOIN message_feedback mf ON mf.message_id = m.id
            WHERE m.metadata->'ragReferences' @> jsonb_build_array(jsonb_build_object('knowledgeId', #{knowledgeId}))
              AND ref->>'knowledgeId' = #{knowledgeId}
              AND ref->>'chunkId' ~ '^[0-9]+$'
            GROUP BY ref->>'chunkId'
            HAVING COUNT(DISTINCT mf.id) FILTER (WHERE mf.rating = 'dislike') > 0
            ORDER BY dislikeCount DESC, likeCount ASC, referenceCount DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> lowRatedChunks(@Param("knowledgeId") String knowledgeId,
                                              @Param("limit") int limit);

    /**
     * 休眠分块列表：最近 days 天内未被引用（含从未被引用的）
     * <p>以 chunk 表为驱动表 LEFT JOIN 引用记录，过滤条件下沉到 HAVING，保证从未被引用的分块也返回</p>
     *
     * @param knowledgeId 知识库ID
     * @param days        休眠阈值天数（最近 days 天内无引用视为休眠）
     * @param limit       最大返回数
     * @return 每行 Map：chunkId / documentId / content / chunkCreateTime / lastReferencedAt / referenceCount
     */
    @Select("""
            WITH ref_stats AS (
                SELECT
                    (ref->>'chunkId')::bigint  AS chunkId,
                    MAX(m.create_time)         AS lastReferencedAt,
                    COUNT(DISTINCT m.id)       AS referenceCount
                FROM message m
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(m.metadata->'ragReferences') = 'array'
                         THEN m.metadata->'ragReferences' ELSE '[]'::jsonb END
                ) AS ref
                WHERE m.metadata->'ragReferences' @> jsonb_build_array(jsonb_build_object('knowledgeId', #{knowledgeId}))
                  AND ref->>'knowledgeId' = #{knowledgeId}
                  AND ref->>'chunkId' ~ '^[0-9]+$'
                GROUP BY ref->>'chunkId'
            )
            SELECT
                c.id                 AS chunkId,
                c.document_id        AS documentId,
                c.content            AS content,
                c.create_time        AS chunkCreateTime,
                rs.lastReferencedAt  AS lastReferencedAt,
                COALESCE(rs.referenceCount, 0) AS referenceCount
            FROM chunk c
            LEFT JOIN ref_stats rs ON rs.chunkId = c.id
            WHERE c.knowledge_id = #{knowledgeId}::bigint
              AND c.status = 'completed'
              AND (rs.lastReferencedAt IS NULL
                   OR rs.lastReferencedAt < CURRENT_DATE - #{days} * INTERVAL '1 day')
            ORDER BY rs.lastReferencedAt NULLS FIRST, c.create_time ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> sleepingChunks(@Param("knowledgeId") String knowledgeId,
                                              @Param("days") int days,
                                              @Param("limit") int limit);

    /**
     * 休眠分块计数（用于概览页统计）
     *
     * @param knowledgeId 知识库ID
     * @param days        休眠阈值天数
     * @return 休眠分块数
     */
    @Select("""
            WITH ref_stats AS (
                SELECT
                    (ref->>'chunkId')::bigint  AS chunkId,
                    MAX(m.create_time)         AS lastReferencedAt
                FROM message m
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(m.metadata->'ragReferences') = 'array'
                         THEN m.metadata->'ragReferences' ELSE '[]'::jsonb END
                ) AS ref
                WHERE m.metadata->'ragReferences' @> jsonb_build_array(jsonb_build_object('knowledgeId', #{knowledgeId}))
                  AND ref->>'knowledgeId' = #{knowledgeId}
                  AND ref->>'chunkId' ~ '^[0-9]+$'
                GROUP BY ref->>'chunkId'
            )
            SELECT COUNT(*)
            FROM chunk c
            LEFT JOIN ref_stats rs ON rs.chunkId = c.id
            WHERE c.knowledge_id = #{knowledgeId}::bigint
              AND c.status = 'completed'
              AND (rs.lastReferencedAt IS NULL
                   OR rs.lastReferencedAt < CURRENT_DATE - #{days} * INTERVAL '1 day')
            """)
    long countSleepingChunks(@Param("knowledgeId") String knowledgeId,
                             @Param("days") int days);
}
