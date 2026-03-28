package com.baimao.oj.ai.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * Scope dimension for AI disable rules.
 */
public enum AiRuleScopeEnum {
    /**
     * Global scope.
     */
    GLOBAL("GLOBAL"),
    /**
     * Contest scope.
     */
    CONTEST("CONTEST"),
    /**
     * Question scope.
     */
    QUESTION("QUESTION"),
    /**
     * User scope.
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
