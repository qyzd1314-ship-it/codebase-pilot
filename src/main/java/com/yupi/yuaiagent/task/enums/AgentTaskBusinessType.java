package com.yupi.yuaiagent.task.enums;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;

public enum AgentTaskBusinessType {
    CODE_UNDERSTANDING,
    BUG_DIAGNOSIS,
    PATCH_SUGGESTION,
    TEST_GENERATION;

    public static AgentTaskBusinessType fromValue(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported businessType: " + value));
    }
}
