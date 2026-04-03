package com.baimao.oj.ai.agent.core;

import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.agent.model.AgentState;

/**
 * Base runtime loop for ai.agent implementations.
 */
public abstract class BaseAgent {

    private final int maxSteps;

    protected BaseAgent(int maxSteps) {
        this.maxSteps = Math.max(maxSteps, 1);
    }

    public AgentResult run(AgentRunContext runContext) {
        AgentState state = new AgentState(runContext);
        initialize(state);
        try {
            for (int index = 0; index < maxSteps; index++) {
                state.nextStep();
                AgentResult result = step(state);
                // act 返回值为null，finish 返回值为最终结果
                if (result != null) {
                    return result;
                }
            }
            return finishBecauseMaxSteps(state);
        } finally {
            cleanup(state);
        }
    }

    protected void initialize(AgentState state) {
    }

    protected abstract AgentResult step(AgentState state);

    protected abstract AgentResult finish(AgentState state);

    protected abstract AgentResult finishBecauseMaxSteps(AgentState state);

    protected void cleanup(AgentState state) {
    }
}
