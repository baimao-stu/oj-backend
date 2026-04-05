package com.baimao.oj.ai.agent.core;

import com.baimao.oj.ai.agent.llm.AgentLlmCaller;
import com.baimao.oj.ai.agent.tools.AgentToolsManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 具体的 agent 实现类
 */
public class ACoderAgent extends ToolsAgent {

    public ACoderAgent(int maxSteps, int maxDecisionRetries, int maxObservationChars,
                                 ObjectMapper objectMapper, AgentLlmCaller llmCaller,
                                 AgentToolsManager agentToolsManager) {
        super(maxSteps, maxDecisionRetries, maxObservationChars, objectMapper, llmCaller, agentToolsManager);
    }
}
