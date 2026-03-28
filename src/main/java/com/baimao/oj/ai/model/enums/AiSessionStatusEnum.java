package com.baimao.oj.ai.model.enums;

/**
 * Status of AI chat session lifecycle.
 */
public enum AiSessionStatusEnum {
    /**
     * Session is active and can continue chatting.
     */
    ACTIVE(0),
    /**
     * Session is archived after retention expiry.
     */
    ARCHIVED(1),
    /**
     * Session is disabled by rule/policy.
     */
    DISABLED(2);

    private final Integer value;

    AiSessionStatusEnum(Integer value) {
        this.value = value;
    }

    public Integer getValue() {
        return value;
    }
}
