package com.baimao.oj.ai.model.enums;

/**
 * AI 会话生命周期状态。
 */
public enum AiSessionStatusEnum {
    /**
     * 会话处于激活状态，可继续对话。
     */
    ACTIVE(0),
    /**
     * 会话因保留期到达而归档。
     */
    ARCHIVED(1),
    /**
     * 会话因规则/策略被禁用。
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

