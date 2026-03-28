package com.baimao.oj.ai.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * Supported AI chat modes.
 */
public enum AiChatModeEnum {
    /**
     * Plain chat mode without autonomous tool orchestration.
     */
    NORMAL("normal"),
    /**
     * Agent mode that can trigger built-in tools.
     */
    AGENT("agent");

    private final String value;

    AiChatModeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AiChatModeEnum fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return NORMAL;
        }
        for (AiChatModeEnum modeEnum : values()) {
            if (modeEnum.value.equalsIgnoreCase(value)) {
                return modeEnum;
            }
        }
        return NORMAL;
    }
}
