package com.baimao.oj.ai.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型在单步规划阶段返回的标准决策对象 -> AI 的思考结果。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDecision {

    /**
     * 对下一步意图的简短思考。
     */
    private String thought;

    /**
     * 给前端或日志展示的简短计划。
     */
    private List<String> plan = new ArrayList<>();

    /**
     * 当前要执行的动作，tool 或 finish。
     */
    private String action;

    /**
     * 当 action=tool 时 (LLM) 要调用的工具名。（System prompt限制了一步最多调用一个Tool）
     */
    private String toolName;

    /**
     * (LLM)传给工具的输入参数。
     */
    private Map<String, Object> toolInput = new LinkedHashMap<>();

    /**
     * 当 action=finish 时可附带的兜底答案。
     */
    private String finalAnswer;

    /**
     * 根据已有字段推断动作类型，兼容模型省略字段的情况。
     */
    public AgentActionType resolveActionType() {
        if (StringUtils.isBlank(action)) {
            if (StringUtils.isNotBlank(toolName)) {
                return AgentActionType.TOOL;
            }
            if (StringUtils.isNotBlank(finalAnswer)) {
                return AgentActionType.FINISH;
            }
        }
        return AgentActionType.fromValue(action);
    }
}
