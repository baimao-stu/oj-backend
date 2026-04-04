package com.baimao.oj.ai.agent.core;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.agent.llm.AgentLlmCaller;
import com.baimao.oj.ai.agent.model.*;
import com.baimao.oj.ai.agent.tools.AgentToolsManager;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.baimao.oj.ai.prompt.PromptUtils.*;
import static com.baimao.oj.ai.service.Constant.EVENT_DELTA;
import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;

/**
 * ReAct agent specialized for tool planning and execution.
 */
public class ToolsAgent extends ReActAgent {

    private final int maxDecisionRetries;
    private final int maxObservationChars;
    private final ObjectMapper objectMapper;
    private final AgentLlmCaller llmCaller;
    private final AgentToolsManager agentToolsManager;

    public ToolsAgent(int maxSteps, int maxDecisionRetries, int maxObservationChars,
                      ObjectMapper objectMapper, AgentLlmCaller llmCaller,
                      AgentToolsManager agentToolsManager) {
        super(maxSteps);
        this.maxDecisionRetries = Math.max(maxDecisionRetries, 1);
        this.maxObservationChars = Math.max(maxObservationChars, 400);
        this.objectMapper = objectMapper;
        this.llmCaller = llmCaller;
        this.agentToolsManager = agentToolsManager;
    }

    @Override
    protected boolean think(AgentState state) {
        AgentDecision decision = decideNextAction(state);
        state.setCurrentDecision(decision);

        AgentActionType actionType = decision == null ? AgentActionType.FINISH : decision.resolveActionType();
        /** 给前端返回当前step的执行计划（思考结果）,真正的执行行动在 act 方法 */
        publishPlannerEvent(state.getRunContext(), state.getCurrentStep(), decision, actionType);
        return actionType == AgentActionType.TOOL;
    }

    @Override
    protected AgentResult act(AgentState state) {
        AgentDecision decision = state.getCurrentDecision();
        String toolName = StringUtils.defaultString(decision.getToolName()).trim();
        /** 工具需要的参数 */
        Map<String, Object> toolInput = decision.getToolInput() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(decision.getToolInput());
        AgentRunContext runContext = state.getRunContext();
        AgentToolsManager.RuntimeContext runtimeContext = new AgentToolsManager.RuntimeContext(
                runContext.getLoginUser().getId(),
                runContext.getQuestion(),
                runContext.getContestId(),
                runContext.getRequestBody(),
                runContext.getEmitter(),
                runContext.getToolEvents()
        );
        /** 工具执行结果：作为观察反馈给 Agent，供下一轮思考使用 */
        String observation = agentToolsManager.executeTool(toolName, toolInput, runtimeContext);
        /** 每执行一个step，保存一个执行链路 */
        state.addTrace(AgentStepTrace.builder()
                .stepNo(state.getCurrentStep())
                .thought(StringUtils.defaultString(decision.getThought()))
                .plan(copyPlan(decision.getPlan()))
                .actionType(AgentActionType.TOOL)
                .toolName(toolName)
                .toolInputJson(toJson(toolInput))
                .observation(StringUtils.defaultString(observation))
                .build());
        return null;
    }

    @Override
    protected AgentResult finish(AgentState state) {
        AgentDecision finalDecision = state.getCurrentDecision();
        String finalAnswer = synthesizeFinalAnswer(state);
        if (StringUtils.isBlank(finalAnswer) && finalDecision != null) {
            finalAnswer = StringUtils.defaultString(finalDecision.getFinalAnswer()).trim();
        }
        String normalizedAnswer = StringUtils.defaultString(finalAnswer).trim();
        return new AgentResult(normalizedAnswer, normalizedAnswer);
    }

    @Override
    protected AgentResult finishBecauseMaxSteps(AgentState state) {
        String finalAnswer = synthesizeFinalAnswer(state);
        String normalizedAnswer = StringUtils.defaultString(finalAnswer).trim();
        return new AgentResult(normalizedAnswer, normalizedAnswer);
    }

    /**
     * 根据 Agent 的状态决定下一步的行动，可能会重试多次以获得符合规范的决策结果。
     * call 方式调用 LLM
     * @param state
     * @return
     */
    private AgentDecision decideNextAction(AgentState state) {
        String parseError = null;
        for (int attempt = 1; attempt <= maxDecisionRetries; attempt++) {
            String rawResponse = llmCaller.call(
                    state.getRunContext(),
                    buildDecisionSystemPrompt(state),
                    buildDecisionUserPrompt(state, parseError, agentToolsManager, maxObservationChars)
            );
            // LLM 的决策，如还需工具调用，则 decision 的 action=tool，否则为 finish
            AgentDecision decision = parseDecision(rawResponse);
            if (isValidDecision(decision)) {
                return decision;
            }
            parseError = StringUtils.defaultIfBlank(rawResponse, "Model returned an empty response.");
        }
        // 思考结果的降级，直接完成整个任务：如果多次尝试仍未得到有效决策isValidDecision(decision)。
        AgentDecision fallback = new AgentDecision();
        fallback.setAction(AgentActionType.FINISH.name());
        fallback.setThought("The planner failed to produce a valid next-step decision, so the task will be wrapped up with the available evidence.");
        fallback.setFinalAnswer("");
        return fallback;
    }

    /**
     * 解析AI返回的内容中包含的 JSON schema（System prompt中要求返回的格式）
     * @param rawResponse
     * @return
     */
    private AgentDecision parseDecision(String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) {
            return null;
        }
        String normalized = extractJsonPayload(rawResponse);
        if (StringUtils.isBlank(normalized)) {
            return null;
        }
        try {
            return objectMapper.readValue(normalized, AgentDecision.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isValidDecision(AgentDecision decision) {
        if (decision == null) {
            return false;
        }
        AgentActionType actionType = decision.resolveActionType();
        if (actionType == AgentActionType.FINISH) {
            return true;
        }
        if (StringUtils.isBlank(decision.getToolName())) {
            return false;
        }
        return agentToolsManager.supportsTool(decision.getToolName());
    }

    /**
     * 综合整个思维链：生成最终回复
     * @param state
     * @return
     */
    private String synthesizeFinalAnswer(AgentState state) {
        AgentRunContext runContext = state.getRunContext();
        String systemPrompt = buildFinalSystemPrompt(state);
        String userPrompt = buildFinalUserPrompt(state, maxObservationChars);
        Consumer<String> deltaConsumer = buildDeltaConsumer(runContext);
        String response = deltaConsumer == null
                ? llmCaller.call(runContext, systemPrompt, userPrompt)
                : llmCaller.stream(runContext, systemPrompt, userPrompt, deltaConsumer);
        if (StringUtils.isNotBlank(response)) {
            return response.trim();
        }
        return "抱歉，我暂时无法完成最终整理，但已经获取到部分证据。请根据上方执行轨迹继续分析。";
    }

    /**
     * SSE 输出 LLM 思考情况（每一个step发送一次思考）给前端展示
     * @param runContext
     * @param stepNo
     * @param decision
     * @param actionType
     */
    private void publishPlannerEvent(AgentRunContext runContext, int stepNo, AgentDecision decision, AgentActionType actionType) {
        String summary = buildPlannerSummary(stepNo, decision, actionType);
        publishEvent(runContext, new AiToolEventVO("planner", "done", summary));
    }

    /**
     * 总结当前步骤 LLM 的思考结果: 将格式化的 AgentDecision 对象转换成一段人类可读的字符串（保存为数据库聊天信息表中的工具调用轨迹）
     */
    private String buildPlannerSummary(int stepNo, AgentDecision decision, AgentActionType actionType) {
        List<String> parts = new ArrayList<>();
        parts.add("Step " + stepNo);
        if (decision != null && StringUtils.isNotBlank(decision.getThought())) {
            parts.add(decision.getThought());
        }
        if (decision != null && decision.getPlan() != null && !decision.getPlan().isEmpty()) {
            parts.add("plan=" + String.join(" -> ", decision.getPlan()));
        }
        if (actionType == AgentActionType.TOOL && decision != null) {
            parts.add("next=" + decision.getToolName());
        } else {
            parts.add("next=finish");
        }
        return String.join(" | ", parts);
    }

    private void publishEvent(AgentRunContext runContext, AiToolEventVO event) {
        runContext.getToolEvents().add(event);
        if (runContext.getEmitter() == null) {
            return;
        }
        try {
            runContext.getEmitter().send(
                    org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name(EVENT_TOOL)
                            .data(JSONUtil.toJsonStr(event))
            );
        } catch (Exception ignore) {
            // Ignore transient SSE send failures here and let the outer request lifecycle handle them.
        }
    }

    private Consumer<String> buildDeltaConsumer(AgentRunContext runContext) {
        if (runContext.getEmitter() == null) {
            return null;
        }
        return chunk -> {
            if (StringUtils.isBlank(chunk)) {
                return;
            }
            try {
                runContext.getEmitter().send(
                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name(EVENT_DELTA)
                                .data(JSONUtil.toJsonStr(chunk))
                );
            } catch (Exception ignore) {
                // Ignore transient SSE send failures here and let the outer request lifecycle handle them.
            }
        };
    }

    /**
     * 从大语言模型 LLM 的原始返回结果（通常是 markdown）中提取出纯净的 JSON 字符串
     * @param rawResponse
     * @return
     */
    private String extractJsonPayload(String rawResponse) {
        String normalized = StringUtils.trimToEmpty(rawResponse);
        // 去除markdown的修饰
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "");
            normalized = normalized.replaceFirst("\\s*```\\s*$", "").trim();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private List<String> copyPlan(List<String> rawPlan) {
        if (rawPlan == null || rawPlan.isEmpty()) {
            return new ArrayList<>();
        }
        return rawPlan.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .toList();
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return JSONUtil.toJsonStr(value);
        }
    }


}
