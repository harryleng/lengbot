package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.vo.LlmTraceDetailVO;
import com.lengbot.dto.LlmTraceRequestDTO;
import com.lengbot.dto.LlmTraceSpanDTO;
import com.lengbot.entity.LlmTrace;
import com.lengbot.entity.Message;
import com.lengbot.enums.ErrorCode;
import com.lengbot.mapper.LlmTraceMapper;
import com.lengbot.mapper.MessageMapper;
import com.lengbot.service.LlmTraceService;
import com.lengbot.service.MentionTraceSnapshotService;
import com.lengbot.util.LlmTraceContext;
import com.lengbot.util.LlmTraceMentionEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM调用链追踪 Service实现
 *
 * @author finch
 * @since 2026-05-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmTraceServiceImpl extends ServiceImpl<LlmTraceMapper, LlmTrace>
        implements LlmTraceService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper objectMapper;
    private final MessageMapper messageMapper;
    private final MentionTraceSnapshotService mentionTraceSnapshotService;

    /**
     * 分页查询调用链列表
     */
    @Override
    public Map<String, Object> pageList(LlmTraceRequestDTO request) {
        // 1. 构建查询条件
        LambdaQueryWrapper<LlmTrace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(request.getStatus()), LlmTrace::getStatus, request.getStatus())
                .eq(request.getSessionId() != null, LlmTrace::getSessionId, request.getSessionId())
                .eq(StringUtils.hasText(request.getRequestId()), LlmTrace::getRequestId, request.getRequestId())
                .eq(request.getAgentId() != null, LlmTrace::getAgentId, request.getAgentId());

        // 时间范围过滤
        if (StringUtils.hasText(request.getStartTime())) {
            wrapper.ge(LlmTrace::getCreateTime, LocalDateTime.parse(request.getStartTime(), FORMATTER));
        }
        if (StringUtils.hasText(request.getEndTime())) {
            wrapper.le(LlmTrace::getCreateTime, LocalDateTime.parse(request.getEndTime(), FORMATTER));
        }

        // 按来源类型筛选：指定 traceSource 时精确匹配，否则展示 chat + workflow（排除辅助能力）
        if (StringUtils.hasText(request.getTraceSource())) {
            wrapper.eq(LlmTrace::getTraceSource, request.getTraceSource());
        } else {
            wrapper.and(w -> w.eq(LlmTrace::getTraceSource, "chat")
                    .or().eq(LlmTrace::getTraceSource, "workflow")
                    .or().isNull(LlmTrace::getTraceSource));
        }

        wrapper.orderByDesc(LlmTrace::getCreateTime);

        // 2. 分页查询
        Page<LlmTrace> page = baseMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                wrapper
        );

        // 3. 组装返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("pageNum", request.getPageNum());
        result.put("pageSize", request.getPageSize());
        return result;
    }

    /**
     * 查询调用链详情（spans JSON解析为对象列表）
     */
    @Override
    public LlmTraceDetailVO getDetail(Long id) {
        LlmTrace trace = getById(id);
        if (trace == null) {
            throw new BizException(ErrorCode.LLM_TRACE_NOT_FOUND);
        }

        // 转换为VO
        LlmTraceDetailVO vo = new LlmTraceDetailVO();
        BeanUtils.copyProperties(trace, vo);

        // 解析spans JSON字符串为对象列表
        if (trace.getSpans() != null && !trace.getSpans().isBlank()) {
            try {
                List<LlmTraceSpanDTO> spans = objectMapper.readValue(trace.getSpans(), new TypeReference<>() {});
                enrichTraceMentions(trace.getSessionId(), spans);
                vo.setSpans(spans);
            } catch (Exception e) {
                log.warn("[LLMTrace] spans解析失败, id={}, error={}", id, e.getMessage());
                vo.setSpans(List.of());
            }
        } else {
            vo.setSpans(List.of());
        }
        return vo;
    }

    /**
     * 从会话消息 metadata 回填 Trace 中历史 user 消息的 mentions（兼容旧 Trace 数据）
     */
    private void enrichTraceMentions(Long sessionId, List<LlmTraceSpanDTO> spans) {
        if (sessionId == null || spans == null || spans.isEmpty()) {
            return;
        }
        List<Message> sessionMessages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .orderByAsc(Message::getCreateTime));
        if (sessionMessages.isEmpty()) {
            return;
        }
        LlmTraceMentionEnricher.enrich(spans, sessionMessages, mentionTraceSnapshotService);
    }

    /**
     * 汇总统计（SQL 聚合，避免全量加载到内存）
     */
    @Override
    public Map<String, Object> getOverview(String traceSource) {
        // 1. SQL 聚合：total_count / total_tokens / avg_duration_ms / total_tool_calls
        String source = StringUtils.hasText(traceSource) ? traceSource : null;
        Map<String, Object> aggregate = baseMapper.aggregateOverview(source);

        long totalCount = ((Number) aggregate.getOrDefault("total_count", 0)).longValue();

        // 2. 成功/失败数（有索引，count 很快）
        LambdaQueryWrapper<LlmTrace> base = chatTraceWrapper(traceSource);
        long successCount = count(base.clone().eq(LlmTrace::getStatus, "completed"));
        long failedCount = count(base.clone().eq(LlmTrace::getStatus, "failed"));

        // 3. 组装返回
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", totalCount);
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("totalTokens", aggregate.getOrDefault("total_tokens", 0));
        result.put("avgDurationMs", totalCount > 0
                ? ((Number) aggregate.getOrDefault("avg_duration_ms", 0)).longValue() : 0);
        result.put("totalToolCalls", aggregate.getOrDefault("total_tool_calls", 0));
        return result;
    }

    /**
     * 构建 trace 来源筛选条件
     */
    private LambdaQueryWrapper<LlmTrace> chatTraceWrapper(String traceSource) {
        if (StringUtils.hasText(traceSource)) {
            return new LambdaQueryWrapper<LlmTrace>().eq(LlmTrace::getTraceSource, traceSource);
        }
        return new LambdaQueryWrapper<LlmTrace>()
                .and(w -> w.eq(LlmTrace::getTraceSource, "chat")
                        .or().eq(LlmTrace::getTraceSource, "workflow")
                        .or().isNull(LlmTrace::getTraceSource));
    }

    @Override
    public LlmTrace findByRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<LlmTrace>()
                .eq(LlmTrace::getRequestId, requestId)
                .eq(LlmTrace::getTraceSource, "workflow")
                .orderByDesc(LlmTrace::getCreateTime)
                .last("LIMIT 1"));
    }

    @Override
    public LlmTrace findLatestByRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<LlmTrace>()
                .eq(LlmTrace::getRequestId, requestId)
                .orderByDesc(LlmTrace::getCreateTime)
                .last("LIMIT 1"));
    }

    @Override
    public void upsertWorkflowTrace(LlmTrace trace) {
        if (LlmTraceContext.isSuppressed()) {
            return;
        }
        if (trace == null || !StringUtils.hasText(trace.getRequestId())) {
            return;
        }
        trace.setTraceSource("workflow");
        try {
            sanitizeTraceFields(trace);
            LlmTrace existing = findByRequestId(trace.getRequestId());
            if (existing != null) {
                if (isFinalized(existing.getStatus()) && !isFinalized(trace.getStatus())) {
                    log.debug("[LLMTrace] 跳过 stale 运行中快照覆盖: requestId={}", trace.getRequestId());
                    return;
                }
                trace.setId(existing.getId());
                updateById(trace);
            } else {
                save(trace);
            }
        } catch (Exception e) {
            log.error("[LLMTrace] upsert 工作流 trace 失败, requestId={}", trace.getRequestId(), e);
        }
    }

    private static boolean isFinalized(String status) {
        return "completed".equals(status) || "failed".equals(status);
    }

    private void sanitizeTraceFields(LlmTrace trace) {
        trace.setReplyContent(com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(trace.getReplyContent()));
        trace.setDisplayContent(com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(trace.getDisplayContent()));
        trace.setErrorMessage(com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(trace.getErrorMessage()));
        trace.setSpans(com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(trace.getSpans()));
    }

    @Async("lengBotExecutor")
    @Override
    public void recordTrace(LlmTrace trace) {
        if (LlmTraceContext.isSuppressed()) {
            log.debug("[LLMTrace] 跳过辅助能力 trace: requestId={}", trace.getRequestId());
            return;
        }
        if (trace.getTraceSource() == null) {
            trace.setTraceSource("chat");
        }
        // 仅允许 chat 和 workflow 来源写入
        if (!"chat".equals(trace.getTraceSource()) && !"workflow".equals(trace.getTraceSource())) {
            return;
        }
        try {
            if ("workflow".equals(trace.getTraceSource()) && StringUtils.hasText(trace.getRequestId())) {
                upsertWorkflowTrace(trace);
                return;
            }
            sanitizeTraceFields(trace);
            save(trace);
        } catch (Exception e) {
            log.error("[LLMTrace] 异步写入调用链失败, requestId={}", trace.getRequestId(), e);
        }
    }

    /**
     * 删除会话下的所有调用链记录
     */
    @Override
    public void deleteBySessionId(Long sessionId) {
        remove(new LambdaQueryWrapper<LlmTrace>().eq(LlmTrace::getSessionId, sessionId));
    }

    /**
     * 批量删除多个会话下的所有调用链记录（一次 IN，替代 N 次循环）
     */
    @Override
    public void deleteBySessionIds(java.util.Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        remove(new LambdaQueryWrapper<LlmTrace>().in(LlmTrace::getSessionId, sessionIds));
    }

    /**
     * 批量删除调用链记录（物理删除）
     */
    @Override
    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        removeByIds(ids);
    }

    /**
     * 按保留期清理过期调用链记录（物理删除）
     */
    @Override
    public int cleanupByAge(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        return baseMapper.deleteByCreateTimeBefore(threshold);
    }
}
