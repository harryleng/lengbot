package com.lengbot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户长期记忆状态
 *
 * @author lw
 * @since 2026-07-09
 */
@Getter
@AllArgsConstructor
public enum UserMemoryStatus {

    ACTIVE("active", "启用"),
    DISABLED("disabled", "停用"),
    ARCHIVED("archived", "归档");

    @EnumValue
    private final String code;

    private final String desc;

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static UserMemoryStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        for (UserMemoryStatus status : values()) {
            if (status.code.equalsIgnoreCase(value)
                    || status.name().equalsIgnoreCase(value)
                    || status.desc.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return ACTIVE;
    }
}
