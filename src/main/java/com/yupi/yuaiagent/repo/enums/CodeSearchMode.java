package com.yupi.yuaiagent.repo.enums;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

public enum CodeSearchMode {

    KEYWORD_ONLY,
    VECTOR_ONLY,
    HYBRID;

    public static CodeSearchMode from(String value) {
        if (StrUtil.isBlank(value)) {
            return HYBRID;
        }
        try {
            return CodeSearchMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HYBRID;
        }
    }
}
