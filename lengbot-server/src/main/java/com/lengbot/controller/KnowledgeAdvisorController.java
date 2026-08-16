package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.service.KnowledgeAdvisorService;
import com.lengbot.vo.KnowledgeAdvisorSummaryVO;
import com.lengbot.vo.LowRatedChunkVO;
import com.lengbot.vo.SleepingChunkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库 Advisor 接口：基于用户反馈聚合给出调优建议
 *
 * @author finch
 * @since 2026-07-20
 */
@Slf4j
@Tag(name = "知识库 Advisor", description = "反馈聚合、低分分块、休眠分块")
@RestController
@RequestMapping("/api/knowledge/{id}/advisor")
@RequiredArgsConstructor
public class KnowledgeAdvisorController {

    private final KnowledgeAdvisorService knowledgeAdvisorService;

    @Operation(summary = "反馈聚合概览")
    @GetMapping("/summary")
    public Result<KnowledgeAdvisorSummaryVO> summary(@PathVariable Long id,
                                                     @RequestParam(defaultValue = "14") int windowDays) {
        return Result.ok(knowledgeAdvisorService.getSummary(id, windowDays));
    }

    @Operation(summary = "低分分块列表（按点踩数倒序）")
    @GetMapping("/low-rated-chunks")
    public Result<List<LowRatedChunkVO>> lowRatedChunks(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(knowledgeAdvisorService.getLowRatedChunks(id, limit));
    }

    @Operation(summary = "休眠分块列表（最近 N 天未被引用）")
    @GetMapping("/sleeping-chunks")
    public Result<List<SleepingChunkVO>> sleepingChunks(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "14") int days,
                                                         @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(knowledgeAdvisorService.getSleepingChunks(id, days, limit));
    }
}
