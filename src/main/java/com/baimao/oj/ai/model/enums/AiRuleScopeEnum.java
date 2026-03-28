package com.baimao.oj.ai.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * AI 禁用规则的作用域维度。
 */
public enum AiRuleScopeEnum {
    /**
     * 全局作用域。
     */
    GLOBAL("GLOBAL"),
    /**
     * 比赛作用域。
     */
    CONTEST("CONTEST"),
    /**
     * 题目作用域。
     */
    QUESTION("QUESTION"),
    /**
     * 用户作用域。
     */
    USER("USER");

    private final String value;

    AiRuleScopeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AiRuleScopeEnum fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return GLOBAL;
        }
        for (AiRuleScopeEnum scopeEnum : values()) {
            if (scopeEnum.value.equalsIgnoreCase(value)) {
                return scopeEnum;
            }
        }
        return GLOBAL;
    }
}

