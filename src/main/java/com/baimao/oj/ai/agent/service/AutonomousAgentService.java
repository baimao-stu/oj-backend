package com.baimao.oj.ai.agent.service;

import com.baimao.oj.ai.agent.core.ReActAgent;
import com.baimao.oj.ai.agent.llm.AgentLlmCaller;
import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.config.AiProperties;
import com.baimao.oj.ai.service.AiDatabaseChatMemory;
import com.baimao.oj.ai.tools.AgentToolsManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Agent 运行入口服务。
 */
@Service
@Slf4j
public class AutonomousAgentService {

    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Resource
    private AiDatabaseChatMemory aiDatabaseChatMemory;

    @Resource
    private AgentToolsManager agentToolsManager;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiProperties aiProperties;

    /**
     * 执行一次完整的自主 Agent 流程。
     */
    public AgentResult run(AgentRunContext runContext) {
        AgentLlmCaller llmCaller = new AgentLlmCaller() {
            @Override
            public String call(AgentRunContext context, String systemPrompt, String userPrompt) {
                return callModel(context, systemPrompt, userPrompt);
            }

            @Override
            public String stream(AgentRunContext context, String systemPrompt, String userPrompt,
                                 Consumer<String> chunkConsumer) {
                return streamModel(context, systemPrompt, userPrompt, chunkConsumer);
            }
        };
        ReActAgent agent = new ReActAgent(
                aiProperties.getAgentMaxSteps(),
                aiProperties.getAgentMaxDecisionRetries(),
                aiProperties.getAgentMaxObservationChars(),
                objectMapper,
                llmCaller,
                agentToolsManager
        );
        return agent.run(runContext);
    }

    /**
     * 普通同步模型调用，适合规划阶段。
     */
    private String callModel(AgentRunContext runContext, String systemPrompt, String userPrompt) {
        try {
            var requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(runContext.getSessionId()))
                                    .build()
                    );
            if (runContext.getSafeGuardAdvisor() != null) {
                requestSpec = requestSpec.advisors(runContext.getSafeGuardAdvisor());
            }
            return requestSpec
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Autonomous agent model call failed", e);
            return null;
        }
    }

    /**
     * 流式模型调用，适合最终答案边生成边推送。
     */
    private String streamModel(AgentRunContext runContext, String systemPrompt, String userPrompt,
                               Consumer<String> chunkConsumer) {
        StringBuilder contentBuilder = new StringBuilder();
        try {
            var requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(runContext.getSessionId()))
                                    .build()
                    );
            if (runContext.getSafeGuardAdvisor() != null) {
                requestSpec = requestSpec.advisors(runContext.getSafeGuardAdvisor());
            }
            requestSpec
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        // 一边累计完整内容，一边把增量片段实时透传给调用方。
                        contentBuilder.append(chunk);
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(chunk);
                        }
                    })
                    .blockLast();
            return contentBuilder.toString();
        } catch (Exception e) {
            log.error("Autonomous agent model stream failed", e);
            return contentBuilder.isEmpty() ? null : contentBuilder.toString();
        }
    }
}
