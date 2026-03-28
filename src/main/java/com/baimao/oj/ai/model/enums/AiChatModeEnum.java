package com.baimao.oj.ai.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 支持的 AI 聊天模式。
 */
public enum AiChatModeEnum {
    /**
     * 普通聊天模式，不进行自主工具编排。
     */
    NORMAL("normal"),
    /**
     * 智能体模式，可触发内置工具。
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

