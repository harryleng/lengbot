package com.lengbot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 限流时间窗口
 *
 * @author finch
 * @since 2026-07-21
 */
@Getter
@AllArgsConstructor
public enum RateLimitWindow implements EnumDisplay {

    MINUTE("MINUTE", "分钟", 60),
    HOUR("HOUR", "小时", 3600),
    DAY("DAY", "天", 86400);

    @EnumValue
    private final String code;

    private final String desc;

    /** 窗口秒数，用于 Redis Key TTL */
    private final long seconds;

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RateLimitWindow fromValue(String value) {
        for (RateLimitWindow e : values()) {
            if (e.code.equalsIgnoreCase(value) || e.desc.equalsIgnoreCase(value) || e.name().equalsIgnoreCase(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的限流窗口: " + value);
    }

    public long toSeconds() {
        return seconds;
    }
}
