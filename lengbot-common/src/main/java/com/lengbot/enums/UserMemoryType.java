package com.lengbot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户长期记忆类型
 *
 * @author lw
 * @since 2026-07-09
 */
@Getter
@AllArgsConstructor
public enum UserMemoryType {

    PREFERENCE("preference", "用户偏好"),
    PROFILE("profile", "用户画像"),
    PROJECT_FACT("project_fact", "项目事实"),
    INSTRUCTION("instruction", "长期指令"),
    /** 踩坑经验：任务中遇到并解决的棘手报错/坑 */
    LESSON("lesson", "踩坑经验"),
    /** 成功案例：可复用的高效做法 */
    CASE("case", "成功案例");

    @EnumValue
    private final String code;

    private final String desc;

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static UserMemoryType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return PREFERENCE;
        }
        for (UserMemoryType type : values()) {
            if (type.code.equalsIgnoreCase(value)
                    || type.name().equalsIgnoreCase(value)
                    || type.desc.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return PREFERENCE;
    }
}
