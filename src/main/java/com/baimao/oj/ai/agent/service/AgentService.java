package com.baimao.oj.ai.agent.service;

import com.baimao.oj.ai.advisor.MyLoggerAdvisor;
import com.baimao.oj.ai.agent.core.ACoderAgent;
import com.baimao.oj.ai.agent.llm.AgentLlmCaller;
import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.agent.tools.AgentToolsManager;
import com.baimao.oj.ai.config.AiProperties;
import com.baimao.oj.ai.service.AiDatabaseChatMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Agent execution entry service.
 */
@Service
@Slf4j
public class AgentService {

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

    public AgentResult run(AgentRunContext runContext) {
        AgentLlmCaller llmCaller = new AgentLlmCaller() {
            @Override
            public String call(AgentRunContext context, String systemPrompt, String userPrompt) {
                return callModel(context, systemPrompt, userPrompt);
            }

            @Override
            public <T> T callForEntity(AgentRunContext context, String systemPrompt, String userPrompt, Class<T> entityType) {
                return callModelEntity(context, systemPrompt, userPrompt, entityType);
            }

            @Override
            public String stream(AgentRunContext context, String systemPrompt, String userPrompt,
                                 Consumer<String> chunkConsumer) {
                return streamModel(context, systemPrompt, userPrompt, chunkConsumer);
            }
        };
        ACoderAgent agent = new ACoderAgent(
                aiProperties.getAgentMaxSteps(),
                aiProperties.getAgentMaxDecisionRetries(),
                aiProperties.getAgentMaxObservationChars(),
                objectMapper,
                llmCaller,
                agentToolsManager
        );
        return agent.run(runContext);
    }

    private String callModel(AgentRunContext runContext, String systemPrompt, String userPrompt) {
        try {
            var requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(runContext.getSessionId()))
                                    .build(),
                            new MyLoggerAdvisor()
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

    private <T> T callModelEntity(AgentRunContext runContext, String systemPrompt,
                                  String userPrompt, Class<T> entityType) {
        try {
            var requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(runContext.getSessionId()))
                                    .build(),
                            new MyLoggerAdvisor()
                    );
            if (runContext.getSafeGuardAdvisor() != null) {
                requestSpec = requestSpec.advisors(runContext.getSafeGuardAdvisor());
            }
            return requestSpec
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(entityType);
        } catch (Exception e) {
            log.error("Autonomous agent model structured call failed", e);
            return null;
        }
    }

    private String streamModel(AgentRunContext runContext, String systemPrompt, String userPrompt,
                               Consumer<String> chunkConsumer) {
        StringBuilder contentBuilder = new StringBuilder();
        try {
            var requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(runContext.getSessionId()))
                                    .build(),
                            new MyLoggerAdvisor()
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
