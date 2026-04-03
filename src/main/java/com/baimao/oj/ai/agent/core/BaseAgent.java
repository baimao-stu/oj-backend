package com.baimao.oj.ai.agent.core;

import com.baimao.oj.ai.agent.model.AgentDecision;
import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.agent.model.AgentState;

/**
 * Agent 执行骨架基类。
 * 统一维护「初始化状态 -> 逐步决策 -> 处理结果 -> 结束收敛」这一套循环流程。
 */
public abstract class BaseAgent {

    /**
     * 单次任务允许执行的最大步数，避免 Agent 无限制循环。
     */
    private final int maxSteps;

    protected BaseAgent(int maxSteps) {
        this.maxSteps = Math.max(maxSteps, 1);
    }

    /**
     * 驱动 Agent 完整运行。
     */
    public AgentResult run(AgentRunContext runContext) {
        AgentState state = new AgentState(runContext);
        for (int index = 0; index < maxSteps; index++) {
            // 每次循环先推进一步，再基于最新状态做决策。
            state.nextStep();
            AgentDecision decision = decideNextAction(state);
            AgentResult result = handleDecision(state, decision);
            if (result != null) {
                return result;
            }
        }
        return finishBecauseMaxSteps(state);
    }

    protected abstract AgentDecision decideNextAction(AgentState state);

    protected abstract AgentResult handleDecision(AgentState state, AgentDecision decision);

    /**
     * 达到最大步数后的兜底收尾逻辑。
     */
    protected abstract AgentResult finishBecauseMaxSteps(AgentState state);
}
