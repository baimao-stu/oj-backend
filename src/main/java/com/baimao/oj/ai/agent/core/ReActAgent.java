package com.baimao.oj.ai.agent.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.agent.llm.AgentLlmCaller;
import com.baimao.oj.ai.agent.model.AgentActionType;
import com.baimao.oj.ai.agent.model.AgentDecision;
import com.baimao.oj.ai.agent.model.AgentResult;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.agent.model.AgentState;
import com.baimao.oj.ai.agent.model.AgentStepTrace;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.ai.tools.AgentToolsManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.baimao.oj.ai.service.Constant.EVENT_DELTA;
import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;

/**
 * 基于 ReAct 模式的 Agent 实现。
 * 每一步都会先规划 Thought / Action，再依据 Observation 继续推理或收尾。
 */
public class ReActAgent extends BaseAgent {

    private static final String ANALYSIS_OPEN = "<analysis>";
    private static final String ANALYSIS_CLOSE = "</analysis>";
    private static final String FINAL_OPEN = "<final>";
    private static final String FINAL_CLOSE = "</final>";

    private final int maxDecisionRetries;
    private final int maxObservationChars;
    private final ObjectMapper objectMapper;
    private final AgentLlmCaller llmCaller;
    private final AgentToolsManager agentToolsManager;

    public ReActAgent(int maxSteps, int maxDecisionRetries, int maxObservationChars,
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
    protected AgentDecision decideNextAction(AgentState state) {
        String parseError = null;
        for (int attempt = 1; attempt <= maxDecisionRetries; attempt++) {
            // 要求模型严格返回 JSON，便于后端稳定解析下一步动作。
            String rawResponse = llmCaller.call(
                    state.getRunContext(),
                    buildDecisionSystemPrompt(state),
                    buildDecisionUserPrompt(state, parseError)
            );
            AgentDecision decision = parseDecision(rawResponse);
            if (isValidDecision(decision)) {
                return decision;
            }
            parseError = StringUtils.defaultIfBlank(rawResponse, "Model returned an empty response.");
        }
        AgentDecision fallback = new AgentDecision();
        fallback.setAction(AgentActionType.FINISH.name());
        fallback.setThought("The planner failed to produce a valid next-step decision, so the task will be wrapped up with the available evidence.");
        fallback.setFinalAnswer("");
        return fallback;
    }

    @Override
    protected AgentResult handleDecision(AgentState state, AgentDecision decision) {
        AgentActionType actionType = decision == null ? AgentActionType.FINISH : decision.resolveActionType();
        publishPlannerEvent(state.getRunContext(), state.getCurrentStep(), decision, actionType);

        if (actionType == AgentActionType.FINISH) {
            // 结束时再单独做一次总结，避免直接把规划结果暴露给用户。
            String finalAnswer = synthesizeFinalAnswer(state);
            if (StringUtils.isBlank(finalAnswer)) {
                finalAnswer = StringUtils.defaultString(decision.getFinalAnswer()).trim();
            }
            String reasoningSummary = buildReasoningSummary(state, decision, null);
            return new AgentResult(composeAssistantContent(reasoningSummary, finalAnswer), finalAnswer, reasoningSummary);
        }

        String toolName = StringUtils.defaultString(decision.getToolName()).trim();
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
        // 工具返回值会作为下一轮推理时的 Observation。
        String observation = agentToolsManager.executeTool(toolName, toolInput, runtimeContext);
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
    protected AgentResult finishBecauseMaxSteps(AgentState state) {
        String finalAnswer = synthesizeFinalAnswer(state);
        String reasoningSummary = buildReasoningSummary(state, null,
                "Reached the configured step limit and synthesized the best grounded answer from the collected observations.");
        return new AgentResult(composeAssistantContent(reasoningSummary, finalAnswer), finalAnswer, reasoningSummary);
    }

    private String buildDecisionSystemPrompt(AgentState state) {
        return """
                You are ACoder running inside a backend-managed autonomous ReAct runtime.
                The runtime executes a strict loop of Thought -> Action -> Observation.
                You are not allowed to directly call tools yourself; instead, you must return a JSON decision that the backend will execute.

                Reference assistant persona and boundaries:
                %s

                Ignore any response-format instructions contained in the reference persona for this step.
                For this step, you MUST return JSON only and nothing else.

                JSON schema:
                {
                  "thought": "1-2 concise sentences explaining the next step",
                  "plan": ["short step 1", "short step 2"],
                  "action": "tool" or "finish",
                  "toolName": "required when action=tool",
                  "toolInput": {},
                  "finalAnswer": "optional fallback answer when action=finish"
                }

                Rules:
                - Use at most one tool per step.
                - Only choose a tool from the provided tool catalog.
                - Prefer finishing as soon as you have enough evidence.
                - Do not fabricate observations, judge results, or code behavior.
                - Keep thought and plan concise because they will be shown in the execution trace.
                """.formatted(StringUtils.defaultIfBlank(state.getRunContext().getBaseSystemPrompt(), "Follow safe, helpful, programming-focused behavior."));
    }

    private String buildDecisionUserPrompt(AgentState state, String parseError) {
        AgentRunContext runContext = state.getRunContext();
        AiChatSendRequest requestBody = runContext.getRequestBody();
        return """
                Current task context:
                - Step: %d
                - Question title: %s
                - Question content:
                %s

                - Programming language: %s
                - Latest judge result: %s
                - Current user code:
                ```%s
                %s
                ```
                - User request: %s

                Available tools:
                %s

                Previous step traces:
                %s

                %s
                Return JSON only.
                """.formatted(
                state.getCurrentStep(),
                StringUtils.defaultString(runContext.getQuestion().getTitle(), "N/A"),
                trimForPrompt(runContext.getQuestion().getContent(), 6000),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "unknown"),
                StringUtils.defaultIfBlank(requestBody.getLatestJudgeResult(), "N/A"),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "text"),
                trimForPrompt(requestBody.getUserCode(), 4000),
                StringUtils.defaultString(requestBody.getMessage()),
                renderToolCatalog(),
                state.renderScratchpad(maxObservationChars),
                StringUtils.isBlank(parseError) ? "" : "The previous response could not be parsed. Fix it and strictly follow the schema. Invalid response: " + trimForPrompt(parseError, 1200)
        );
    }

    private String renderToolCatalog() {
        List<AgentToolsManager.AgentToolDefinition> definitions = agentToolsManager.listEnabledTools();
        if (definitions.isEmpty()) {
            return "- No tools are currently enabled. You should finish directly.";
        }
        List<String> lines = new ArrayList<>();
        for (AgentToolsManager.AgentToolDefinition definition : definitions) {
            lines.add("- " + definition.name() + ": " + definition.description()
                    + " | input schema: " + definition.inputSchema());
        }
        return String.join("\n", lines);
    }

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

    private String synthesizeFinalAnswer(AgentState state) {
        AgentRunContext runContext = state.getRunContext();
        String systemPrompt = buildFinalSystemPrompt(state);
        String userPrompt = buildFinalUserPrompt(state);
        Consumer<String> deltaConsumer = buildDeltaConsumer(runContext);
        String response = deltaConsumer == null
                ? llmCaller.call(runContext, systemPrompt, userPrompt)
                : llmCaller.stream(runContext, systemPrompt, userPrompt, deltaConsumer);
        if (StringUtils.isNotBlank(response)) {
            return response.trim();
        }
        return "抱歉，我暂时无法完成最终整理，但已经获取到部分证据。请根据上方执行轨迹继续分析。";
    }

    private String buildFinalSystemPrompt(AgentState state) {
        return """
                You are ACoder and now need to deliver the final answer for the user.

                Reference assistant persona and boundaries:
                %s

                Ignore any previous response-format instructions in the reference persona.
                For this final synthesis call, output markdown only.
                Ground the answer in the collected observations and the current question/code context.
                If you identify a bug or bottleneck, point it out clearly before giving suggestions.
                """.formatted(StringUtils.defaultIfBlank(state.getRunContext().getBaseSystemPrompt(), "Be a precise programming assistant."));
    }

    private String buildFinalUserPrompt(AgentState state) {
        AgentRunContext runContext = state.getRunContext();
        AiChatSendRequest requestBody = runContext.getRequestBody();
        return """
                Produce the final markdown answer for the user based on the following information.

                Question title: %s
                Question content:
                %s

                Programming language: %s
                Latest judge result: %s
                Current user code:
                ```%s
                %s
                ```

                User request:
                %s

                Collected execution trace:
                %s
                """.formatted(
                StringUtils.defaultString(runContext.getQuestion().getTitle(), "N/A"),
                trimForPrompt(runContext.getQuestion().getContent(), 6000),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "unknown"),
                StringUtils.defaultIfBlank(requestBody.getLatestJudgeResult(), "N/A"),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "text"),
                trimForPrompt(requestBody.getUserCode(), 4000),
                StringUtils.defaultString(requestBody.getMessage()),
                state.renderScratchpad(maxObservationChars)
        );
    }

    private String buildReasoningSummary(AgentState state, AgentDecision finalDecision, String tailNote) {
        List<String> lines = new ArrayList<>();
        for (AgentStepTrace trace : state.getStepTraces()) {
            StringBuilder line = new StringBuilder("- Step ").append(trace.getStepNo()).append(": ");
            List<String> parts = new ArrayList<>();
            if (StringUtils.isNotBlank(trace.getThought())) {
                parts.add(trace.getThought());
            }
            if (trace.getPlan() != null && !trace.getPlan().isEmpty()) {
                parts.add("plan=" + String.join(" -> ", trace.getPlan()));
            }
            if (StringUtils.isNotBlank(trace.getToolName())) {
                parts.add("action=" + trace.getToolName());
            }
            if (StringUtils.isNotBlank(trace.getObservation())) {
                parts.add("observation=" + trimForPrompt(trace.getObservation(), 220));
            }
            line.append(String.join("; ", parts));
            lines.add(line.toString());
        }
        if (finalDecision != null && StringUtils.isNotBlank(finalDecision.getThought())) {
            lines.add("- Final reasoning: " + finalDecision.getThought());
        }
        if (StringUtils.isNotBlank(tailNote)) {
            lines.add("- " + tailNote);
        }
        return String.join("\n", lines);
    }

    private String composeAssistantContent(String reasoningSummary, String finalAnswer) {
        return ANALYSIS_OPEN + "\n"
                + StringUtils.defaultString(reasoningSummary).trim() + "\n"
                + ANALYSIS_CLOSE + "\n"
                + FINAL_OPEN + "\n"
                + StringUtils.defaultString(finalAnswer).trim() + "\n"
                + FINAL_CLOSE;
    }

    private void publishPlannerEvent(AgentRunContext runContext, int stepNo, AgentDecision decision, AgentActionType actionType) {
        String summary = buildPlannerSummary(stepNo, decision, actionType);
        publishEvent(runContext, new AiToolEventVO("planner", "done", summary));
    }

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
            // SSE 事件用于把规划和工具执行过程实时推送给前端。
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

    private String extractJsonPayload(String rawResponse) {
        String normalized = StringUtils.trimToEmpty(rawResponse);
        if (normalized.startsWith("```")) {
            // 兼容模型偶尔返回 Markdown 代码块包裹 JSON 的情况。
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

    private String trimForPrompt(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "N/A";
        }
        // 长文本统一裁剪，避免提示词体积失控。
        return StrUtil.sub(text, 0, maxLength);
    }
}
