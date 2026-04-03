package com.baimao.oj.ai.agent.model;

import org.apache.commons.lang3.StringUtils;

/**
 * Agent 当前步骤允许选择的动作类型。
 */
public enum AgentActionType {
    /**
     * 调用工具获取新的观察结果。
     */
    TOOL,
    /**
     * 结束推理并输出最终答案。
     */
    FINISH;

    /**
     * 将模型返回的动作字符串转换为枚举值。
     */
    public static AgentActionType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return FINISH;
        }
        for (AgentActionType actionType : values()) {
            if (actionType.name().equalsIgnoreCase(value)) {
                return actionType;
            }
        }
        return FINISH;
    }
}
