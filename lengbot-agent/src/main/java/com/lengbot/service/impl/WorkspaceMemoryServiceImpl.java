package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.DailyLog;
import com.lengbot.entity.ProjectMemory;
import com.lengbot.enums.UserMemoryStatus;
import com.lengbot.enums.UserMemoryType;
import com.lengbot.mapper.DailyLogMapper;
import com.lengbot.mapper.ProjectMemoryMapper;
import com.lengbot.service.TextEmbeddingService;
import com.lengbot.service.WorkspaceMemoryService;
import com.lengbot.util.ModelCalls;
import com.lengbot.util.VectorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区记忆 + 每日日志 服务实现。
 * <p>读写逻辑镜像 {@code UserMemoryServiceImpl}：向量语义检索优先、关键词兜底、字符截断、使用时间标记；
 * 每日日志按 userId + log_date 维护一条记录，raw_entries 为 JSON 数组。当前按 userId 维度
 * （workspaceId 传 null=全局），后续可扩展按工作区隔离。</p>
 *
 * @author lw
 * @since 2026-08-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceMemoryServiceImpl implements WorkspaceMemoryService {

    private static final int MAX_PROMPT_CHARS = 1500;
    private static final int MAX_MEMORIES = 15;
    private static final BigDecimal DEFAULT_CONFIDENCE = BigDecimal.ONE;

    private final ProjectMemoryMapper projectMemoryMapper;
    private final DailyLogMapper dailyLogMapper;
    private final TextEmbeddingService textEmbeddingService;
    private final ObjectMapper objectMapper;

    // ====================== 读取（注入用） ======================

    @Override
    /**
     * 构建工作区记忆 prompt（注入用）。
     * @param userId 用户ID；当前记忆按 userId 全局维度存取
     * @param query 当前用户消息，用于向量语义检索
     */
    public String buildWorkspaceMemoryPrompt(Long userId, String query) {
        if (userId == null) {
            return "";
        }
        List<ProjectMemory> memories = searchForPrompt(userId, null, query, MAX_MEMORIES);
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (ProjectMemory memory : memories) {
            String line = "- " + labelOf(memory.getMemoryType()) + "：" + memory.getContent().trim() + "\n";
            if (chars + line.length() > MAX_PROMPT_CHARS) {
                break;
            }
            sb.append(line);
            chars += line.length();
        }
        return sb.toString();
    }

    @Override
    /**
     * 构建每日工作日志 prompt（注入用）。
     * @param userId 用户ID；当前按 userId 全局维度存取
     */
    public String buildDailyLogPrompt(Long userId) {
        if (userId == null) {
            return "";
        }
        DailyLog log = dailyLogMapper.selectByUserAndDate(userId, LocalDate.now());
        if (log == null || log.getRawEntries() == null || log.getRawEntries().isBlank()) {
            return "";
        }
        List<Map<String, Object>> entries = parseEntries(log.getRawEntries());
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (Map<String, Object> entry : entries) {
            String line = "- " + formatEntry(entry) + "\n";
            if (chars + line.length() > MAX_PROMPT_CHARS) {
                break;
            }
            sb.append(line);
            chars += line.length();
        }
        // 若有摘要优先展示摘要，再列要点
        if (log.getSummary() != null && !log.getSummary().isBlank()) {
            return log.getSummary().trim() + "\n" + sb;
        }
        return sb.toString();
    }

    // ====================== 写入 ======================

    @Override
    /**
     * 保存工作区记忆。
     * @param workspaceId 预留字段：传 null 表示全局维度；待接入工作区隔离后启用
     */
    public Long saveWorkspaceMemory(Long userId, Long workspaceId, Long sessionId, Long sourceMessageId,
                                    String memoryType, String content, List<String> keywords, BigDecimal confidence) {
        ProjectMemory memory = new ProjectMemory();
        memory.setUserId(userId);
        memory.setWorkspaceId(workspaceId);
        memory.setSessionId(sessionId);
        memory.setSourceMessageId(sourceMessageId);
        memory.setMemoryType(UserMemoryType.fromValue(memoryType));
        memory.setContent(content != null ? content.trim() : "");
        memory.setKeywords(toJsonKeywords(keywords, memory.getContent()));
        memory.setConfidence(confidence != null ? confidence : DEFAULT_CONFIDENCE);
        memory.setStatus(UserMemoryStatus.ACTIVE);
        memory.setDeleted(0);
        projectMemoryMapper.insert(memory);
        refreshEmbedding(memory);
        return memory.getId();
    }

    @Override
    /**
     * 追加每日工作日志条目。
     * @param workspaceId 预留字段：当前传 null 表示全局维度
     */
    public void appendDailyLog(Long userId, Long workspaceId, Long sessionId, String type, String content) {
        if (userId == null || content == null || content.isBlank()) {
            return;
        }
        LocalDate today = LocalDate.now();
        DailyLog log = dailyLogMapper.selectByUserAndDate(userId, today);
        List<Map<String, Object>> entries = new ArrayList<>();
        if (log != null && log.getRawEntries() != null && !log.getRawEntries().isBlank()) {
            entries = parseEntries(log.getRawEntries());
        }
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().toString());
        entry.put("type", type != null ? type : "note");
        entry.put("content", content.trim());
        entries.add(entry);

        if (log == null) {
            log = new DailyLog();
            log.setUserId(userId);
            log.setWorkspaceId(workspaceId);
            log.setLogDate(today);
            log.setDeleted(0);
        }
        log.setRawEntries(writeEntries(entries));
        if (log.getId() == null) {
            dailyLogMapper.insert(log);
        } else {
            dailyLogMapper.updateById(log);
        }
    }

    @Override
    public void recordTurn(Long userId, Long sessionId, Long agentId, String userMessage, String assistantReply) {
        if (userId == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        // 轻量记录：用户本轮诉求（截断避免单条过大），不调用 LLM 归纳
        String trimmed = userMessage.trim();
        if (trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500);
        }
        try {
            appendDailyLog(userId, null, sessionId, "chat", trimmed);
        } catch (Exception e) {
            log.warn("[WorkspaceMemory] 记录每日工作日志失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    // ====================== 内部：检索 ======================

    private List<ProjectMemory> searchForPrompt(Long userId, Long workspaceId, String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_MEMORIES));
        List<ProjectMemory> semantic = searchSemanticSafely(userId, workspaceId, query, safeLimit);
        if (!semantic.isEmpty()) {
            markUsed(semantic);
            return semantic;
        }
        LambdaQueryWrapper<ProjectMemory> wrapper = new LambdaQueryWrapper<ProjectMemory>()
                .eq(ProjectMemory::getUserId, userId)
                .eq(ProjectMemory::getStatus, UserMemoryStatus.ACTIVE)
                .orderByDesc(ProjectMemory::getConfidence)
                .orderByDesc(ProjectMemory::getLastUsedAt)
                .orderByDesc(ProjectMemory::getUpdateTime)
                .last("LIMIT " + Math.max(safeLimit * 2, safeLimit));
        // 扩展点：传入非 null workspaceId 时按工作区过滤（当前调用方均传 null，分支不触发）
        if (workspaceId != null) {
            wrapper.and(w -> w.isNull(ProjectMemory::getWorkspaceId).or().eq(ProjectMemory::getWorkspaceId, workspaceId));
        } else {
            wrapper.isNull(ProjectMemory::getWorkspaceId);
        }
        List<ProjectMemory> memories = projectMemoryMapper.selectList(wrapper);
        markUsed(memories);
        return memories.stream().limit(safeLimit).toList();
    }

    private List<ProjectMemory> searchSemanticSafely(Long userId, Long workspaceId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] vector = ModelCalls.toFloatArray(textEmbeddingService.embed(query));
            return projectMemoryMapper.searchSemantic(userId, workspaceId,
                    VectorUtil.toVectorString(vector), limit);
        } catch (Exception e) {
            log.debug("[WorkspaceMemory] 语义检索不可用，降级为置信度排序: {}", e.getMessage());
            return List.of();
        }
    }

    private void markUsed(List<ProjectMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        memories.forEach(m -> {
            try {
                m.setLastUsedAt(now);
                projectMemoryMapper.updateById(m);
            } catch (Exception ignored) {
                // 更新使用时间失败不影响主流程
            }
        });
    }

    private void refreshEmbedding(ProjectMemory memory) {
        try {
            double[] vector = textEmbeddingService.embed(memory.getContent());
            projectMemoryMapper.updateEmbeddingVector(memory.getId(),
                    VectorUtil.toVectorString(ModelCalls.toFloatArray(vector)));
        } catch (Exception e) {
            log.debug("[WorkspaceMemory] 记忆向量生成失败，保留文本记忆: memoryId={}, error={}",
                    memory.getId(), e.getMessage());
        }
    }

    // ====================== 内部：JSON / 工具 ======================

    private List<Map<String, Object>> parseEntries(String json) {
        try {
            List<?> list = objectMapper.readValue(json, List.class);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) o;
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[WorkspaceMemory] 解析 raw_entries 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeEntries(List<Map<String, Object>> entries) {
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            log.warn("[WorkspaceMemory] 序列化 raw_entries 失败: {}", e.getMessage());
            return "[]";
        }
    }

    private String formatEntry(Map<String, Object> entry) {
        Object type = entry.get("type");
        Object content = entry.get("content");
        if (type != null && content != null) {
            return "[" + type + "] " + content;
        }
        return content != null ? content.toString() : "";
    }

    private String toJsonKeywords(List<String> keywords, String content) {
        Set<String> set = new LinkedHashSet<>();
        if (keywords != null) {
            set.addAll(keywords);
        }
        if (content != null && !content.isBlank()) {
            for (String token : content.split("[\\s,，。.；;]+")) {
                if (!token.isBlank() && token.length() <= 20) {
                    set.add(token);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(set));
        } catch (Exception e) {
            return "[]";
        }
    }

    private String labelOf(UserMemoryType type) {
        if (type == null) {
            return "记忆";
        }
        return switch (type) {
            case PREFERENCE -> "偏好";
            case PROFILE -> "画像";
            case PROJECT_FACT -> "项目事实";
            case INSTRUCTION -> "长期指令";
            case LESSON -> "踩坑经验";
            case CASE -> "成功案例";
            default -> "记忆";
        };
    }
}
