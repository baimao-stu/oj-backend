package com.baimao.oj.ai.agent.llm;

import com.baimao.oj.ai.agent.model.AgentRunContext;
import java.util.function.Consumer;

/**
 * Agent 调用大模型的抽象接口。
 */
public interface AgentLlmCaller {

    /**
     * 同步调用模型并一次性返回完整结果。
     */
    String call(AgentRunContext runContext, String systemPrompt, String userPrompt);

    /**
     * 流式调用模型，并在增量内容产生时回调给上层。
     */
    String stream(AgentRunContext runContext, String systemPrompt, String userPrompt, Consumer<String> chunkConsumer);
}
