package com.baimao.oj.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 单步执行轨迹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepTrace {

    /**
     * 第几步，从 1 开始。
     */
    private Integer stepNo;

    /**
     * 当前步骤的简短思考。
     */
    private String thought;

    /**
     * 当前步骤的计划拆解。
     */
    @Builder.Default
    private List<String> plan = new ArrayList<>();

    /**
     * 当前步骤选择的动作类型。
     */
    private AgentActionType actionType;

    /**
     * 实际执行的工具名称。
     */
    private String toolName;

    /**
     * 工具输入参数的 JSON 字符串。
     */
    private String toolInputJson;

    /**
     * 工具执行结果（用于LLM观察）。
     */
    private String observation;
}
