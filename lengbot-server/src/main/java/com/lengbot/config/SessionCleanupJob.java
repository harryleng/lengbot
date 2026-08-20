package com.lengbot.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lengbot.entity.ChatSession;
import com.lengbot.enums.SessionStatus;
import com.lengbot.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归档会话 TTL 清理任务
 * <p>message / llm_trace / MinIO 会话文件随归档会话物理删除而级联清理；
 * 已归档会话无界累积会导致存储无限增长，本任务按保留期批量删除过期归档会话。
 * 默认每天凌晨 3 点执行一次，保留天数由 {@code lengbot.session-cleanup.retention-days} 配置。</p>
 * <p>仅清理「已归档」会话，活跃会话不动，避免误删用户进行中的对话。</p>
 *
 * @author finch
 * @since 2026-08-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupJob {

    private final ChatSessionService chatSessionService;

    /** 归档会话保留天数，默认 90 天；配置为 0 或负数时禁用清理 */
    @Value("${lengbot.session-cleanup.retention-days:90}")
    private int retentionDays;

    /**
     * 每天凌晨 3:00 执行清理（错开日志清理任务，降低 DB 压力）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        if (retentionDays <= 0) {
            log.info("[SessionCleanup] retention-days={} 跳过清理", retentionDays);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("[SessionCleanup] 开始清理 {} 天前的归档会话, cutoff={}", retentionDays, cutoff);
        List<ChatSession> expired;
        try {
            // TableLogic 自动过滤已逻辑删除的会话
            expired = chatSessionService.list(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getStatus, SessionStatus.ARCHIVED)
                    .lt(ChatSession::getUpdateTime, cutoff)
                    .select(ChatSession::getId, ChatSession::getUserId));
        } catch (Exception e) {
            log.error("[SessionCleanup] 查询过期归档会话失败", e);
            return;
        }
        if (expired == null || expired.isEmpty()) {
            log.info("[SessionCleanup] 无过期归档会话");
            return;
        }
        // 按 userId 分组，复用批量删除（级联 message / llm_trace / MinIO，并失效缓存）
        Map<Long, List<Long>> byUser = new LinkedHashMap<>();
        for (ChatSession session : expired) {
            if (session.getId() == null || session.getUserId() == null) {
                continue;
            }
            byUser.computeIfAbsent(session.getUserId(), k -> new ArrayList<>()).add(session.getId());
        }
        int total = 0;
        for (Map.Entry<Long, List<Long>> entry : byUser.entrySet()) {
            try {
                chatSessionService.deleteSessions(entry.getKey(), entry.getValue());
                total += entry.getValue().size();
            } catch (Exception e) {
                log.warn("[SessionCleanup] 删除会话失败: userId={}, ids={}, error={}",
                        entry.getKey(), entry.getValue().size(), e.getMessage());
            }
        }
        log.info("[SessionCleanup] 完成: 清理归档会话={} 个", total);
    }
}
