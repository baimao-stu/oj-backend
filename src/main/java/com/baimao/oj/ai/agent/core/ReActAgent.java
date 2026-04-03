package com.baimao.oj.ai.agent.core;

import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentState;

/**
 * ReAct-style agent skeleton: think first, then optionally act.
 */
public abstract class ReActAgent extends BaseAgent {

    protected ReActAgent(int maxSteps) {
        super(maxSteps);
    }

    @Override
    protected AgentResult step(AgentState state) {
        boolean shouldAct = think(state);
        if (shouldAct) {
            return act(state);
        }
        state.markFinished();
        return finish(state);
    }

    protected abstract boolean think(AgentState state);

    protected abstract AgentResult act(AgentState state);
}
