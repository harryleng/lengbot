package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.vo.GraphEdgeVO;
import com.lengbot.dto.GraphExtractDTO;
import com.lengbot.dto.GraphImportDTO;
import com.lengbot.vo.GraphNodeVO;
import com.lengbot.vo.GraphStatsVO;
import com.lengbot.vo.GraphSubgraphVO;
import com.lengbot.service.GraphService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库图谱管理接口
 *
 * @author lw
 * @since 2026-06-21
 */
@Tag(name = "知识库图谱管理", description = "知识图谱抽取、导入、查询、节点/关系管理")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final GraphService graphService;

    @Operation(summary = "触发图谱抽取（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/graph/extract")
    public Result<Long> extractGraph(@PathVariable Long id,
                                     @Valid @RequestBody GraphExtractDTO request) {
        return Result.ok(graphService.extractFromDocument(id, request));
    }

    @Operation(summary = "批量导入三元组（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/graph/import")
    public Result<GraphStatsVO> importTriples(@PathVariable Long id,
                                              @Valid @RequestBody GraphImportDTO request,
                                              @RequestParam(required = false) Long providerId) {
        return Result.ok(graphService.importTriples(id, request.getTriples(), providerId));
    }

    @Operation(summary = "获取子图数据（可视化用）")
    @GetMapping("/{id}/graph/subgraph")
    public Result<GraphSubgraphVO> getSubgraph(@PathVariable Long id,
                                               @RequestParam(required = false) Long documentId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "2") int maxDepth,
                                               @RequestParam(defaultValue = "50") int maxNodes) {
        return Result.ok(graphService.getSubgraph(id, documentId, keyword, maxDepth, maxNodes));
    }

    @Operation(summary = "语义搜索图谱节点")
    @GetMapping("/{id}/graph/search")
    public Result<List<GraphNodeVO>> semanticSearch(@PathVariable Long id,
                                                    @RequestParam String query,
                                                    @RequestParam(defaultValue = "10") int topK,
                                                    @RequestParam(required = false) Double minScore,
                                                    @RequestParam(required = false) Long documentId,
                                                    @RequestParam(required = false) Long providerId) {
        return Result.ok(graphService.semanticSearch(id, documentId, query, topK, minScore, providerId));
    }

    @Operation(summary = "获取图谱统计信息")
    @GetMapping("/{id}/graph/stats")
    public Result<GraphStatsVO> getGraphStats(@PathVariable Long id,
                                              @RequestParam(required = false) Long documentId) {
        return Result.ok(graphService.getStats(id, documentId));
    }

    @Operation(summary = "清空知识库图谱数据（需要MANAGER及以上权限）")
    @DeleteMapping("/{id}/graph")
    public Result<Void> deleteGraph(@PathVariable Long id) {
        graphService.deleteByKnowledgeId(id);
        return Result.ok();
    }

    @Operation(summary = "删除单个文档的图谱数据（需要DEVELOPER及以上权限）")
    @DeleteMapping("/{id}/graph/documents/{documentId}")
    public Result<Void> deleteDocGraph(@PathVariable Long id, @PathVariable Long documentId) {
        graphService.deleteByDocumentId(id, documentId);
        return Result.ok();
    }

    @Operation(summary = "批量检查哪些文档已有图谱数据")
    @GetMapping("/{id}/graph/existing-docs")
    public Result<List<Long>> getExistingDocIds(@PathVariable Long id,
                                                @RequestParam List<Long> documentIds) {
        return Result.ok(graphService.getExistingDocIds(id, documentIds));
    }

    @Operation(summary = "手动创建图谱节点（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/graph/nodes")
    public Result<GraphNodeVO> createGraphNode(@PathVariable Long id,
                                               @RequestParam String name,
                                               @RequestParam(defaultValue = "其他") String entityType,
                                               @RequestParam(required = false) String description) {
        return Result.ok(graphService.createNode(id, name, entityType, description));
    }

    @Operation(summary = "删除图谱节点（需要DEVELOPER及以上权限）")
    @DeleteMapping("/{id}/graph/nodes/{elementId}")
    public Result<Void> deleteGraphNode(@PathVariable Long id, @PathVariable String elementId) {
        graphService.deleteNode(id, elementId);
        return Result.ok();
    }

    @Operation(summary = "手动创建图谱关系（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/graph/edges")
    public Result<GraphEdgeVO> createGraphEdge(@PathVariable Long id,
                                               @RequestParam String headName,
                                               @RequestParam String relationType,
                                               @RequestParam String tailName,
                                               @RequestParam(required = false) String description) {
        return Result.ok(graphService.createEdge(id, headName, relationType, tailName, description));
    }

    @Operation(summary = "删除图谱关系（需要DEVELOPER及以上权限）")
    @DeleteMapping("/{id}/graph/edges/{elementId}")
    public Result<Void> deleteGraphEdge(@PathVariable Long id, @PathVariable String elementId) {
        graphService.deleteEdge(id, elementId);
        return Result.ok();
    }
}
