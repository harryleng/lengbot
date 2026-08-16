package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lengbot.entity.Document;
import com.lengbot.enums.DocumentStatus;
import com.lengbot.mapper.ChunkMapper;
import com.lengbot.mapper.DocumentMapper;
import com.lengbot.mapper.KnowledgeMapper;
import com.lengbot.service.port.KnowledgeDashboardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库域 Dashboard 统计实现。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDashboardPortImpl implements KnowledgeDashboardPort {

    private final KnowledgeMapper knowledgeMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;

    @Override
    public long countKnowledge() {
        return knowledgeMapper.selectCount(null);
    }

    @Override
    public long countDocuments() {
        return documentMapper.selectCount(null);
    }

    @Override
    public long countChunks() {
        return chunkMapper.selectCount(null);
    }

    @Override
    public Map<String, Object> getKnowledgeStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalKnowledge", countKnowledge());
        stats.put("totalDocuments", countDocuments());
        stats.put("totalChunks", countChunks());

        Map<String, Long> docStatusCounts = new LinkedHashMap<>();
        for (DocumentStatus status : DocumentStatus.values()) {
            Long count = documentMapper.selectCount(
                    new LambdaQueryWrapper<Document>().eq(Document::getStatus, status));
            docStatusCounts.put(status.getCode(), count);
        }
        stats.put("documentStatusCounts", docStatusCounts);

        List<Document> recentDocs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .orderByDesc(Document::getCreateTime)
                        .last("LIMIT 3"));
        List<Map<String, Object>> recentDocList = recentDocs.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId().toString());
            item.put("name", d.getName());
            item.put("knowledgeId", d.getKnowledgeId() != null ? d.getKnowledgeId().toString() : null);
            item.put("createTime", d.getCreateTime());
            return item;
        }).collect(Collectors.toList());
        stats.put("recentDocuments", recentDocList);

        return stats;
    }
}
