package com.lengbot.service.impl;

import com.lengbot.mapper.ChatSessionMapper;
import com.lengbot.mapper.MessageMapper;
import com.lengbot.service.port.ChatDashboardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话域 Dashboard 统计实现。
 */
@Service
@RequiredArgsConstructor
public class ChatDashboardPortImpl implements ChatDashboardPort {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int MAX_TREND_DAYS = 90;

    private final ChatSessionMapper chatSessionMapper;
    private final MessageMapper messageMapper;

    @Override
    public long countSessions() {
        return chatSessionMapper.selectCount(null);
    }

    @Override
    public long countMessages() {
        return messageMapper.selectCount(null);
    }

    @Override
    public Map<String, Object> getChatStats(Integer days, String startDate, String endDate) {
        Map<String, Object> stats = new LinkedHashMap<>();

        LocalDate rangeStart;
        LocalDate rangeEnd;
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            rangeStart = parseDate(startDate);
            rangeEnd = parseDate(endDate);
            if (rangeEnd.isBefore(rangeStart)) {
                LocalDate tmp = rangeStart;
                rangeStart = rangeEnd;
                rangeEnd = tmp;
            }
        } else {
            int trendDays = days != null && days > 0 ? Math.min(days, MAX_TREND_DAYS) : 7;
            rangeEnd = LocalDate.now();
            rangeStart = rangeEnd.minusDays(trendDays - 1L);
            stats.put("trendDays", trendDays);
        }

        long span = ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1;
        if (span > MAX_TREND_DAYS) {
            rangeStart = rangeEnd.minusDays(MAX_TREND_DAYS - 1L);
        }

        // 区间统计：会话按 create_time、消息按 create_time 过滤到选中日期范围内
        String rangeStartStr = rangeStart.format(DATE_FMT);
        String rangeEndStr = rangeEnd.format(DATE_FMT);
        stats.put("totalSessions", chatSessionMapper.countByCreateDateRange(rangeStartStr, rangeEndStr));
        stats.put("totalMessages", messageMapper.countByDateRange(rangeStartStr, rangeEndStr));

        List<Map<String, Object>> raw = messageMapper.countMessagesPerDayRange(rangeStartStr, rangeEndStr);
        stats.put("messagesPerDay", fillMissingDays(raw, rangeStart, rangeEnd));
        stats.put("trendStartDate", rangeStartStr);
        stats.put("trendEndDate", rangeEndStr);
        return stats;
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式无效，应为 yyyy-MM-dd: " + dateStr);
        }
    }

    private List<Map<String, Object>> fillMissingDays(List<Map<String, Object>> trend,
                                                      LocalDate start, LocalDate end) {
        Map<String, Long> countMap = new HashMap<>();
        if (trend != null) {
            for (Map<String, Object> row : trend) {
                String date = String.valueOf(row.get("date"));
                Object countObj = row.get("count");
                long count = countObj instanceof Number n ? n.longValue() : 0L;
                countMap.put(date, count);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String key = d.format(DATE_FMT);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", key);
            item.put("count", countMap.getOrDefault(key, 0L));
            result.add(item);
        }
        return result;
    }
}
