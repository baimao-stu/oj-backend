package com.baimao.oj.ai.prompt;

import cn.hutool.core.util.StrUtil;
import com.baimao.oj.ai.agent.model.AgentRunContext;
import com.baimao.oj.ai.agent.model.AgentState;
import com.baimao.oj.ai.agent.tools.AgentToolsManager;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public class PromptUtils {

    public static String buildDecisionSystemPrompt(AgentState state) {
        return """
                You are running inside a backend-managed autonomous ReAct runtime.
                The runtime executes a strict loop of Thought -> Action -> Observation.
                You are not allowed to directly call tools yourself; instead, you must return a JSON decision that the backend will execute.

                Reference assistant persona and boundaries:
                %s

                Ignore any response-format instructions contained in the reference persona for this step.
                For this step, you MUST return JSON only and nothing else.

                JSON schema:
                {
                  "thought": "1-2 concise sentences explaining the next step",
                  "plan": ["step 1", "step 2"],
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

    public static String buildDecisionUserPrompt(AgentState state, String parseError,AgentToolsManager agentToolsManager,Integer maxObservationChars) {
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
                StringUtils.defaultString(runContext.getQuestion().getTitle(), ""),
                trimForPrompt(runContext.getQuestion().getContent(), 2000),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "unknown"),
                StringUtils.defaultIfBlank(requestBody.getLatestJudgeResult(), ""),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "text"),
                trimForPrompt(requestBody.getUserCode(), 2000),
                StringUtils.defaultString(requestBody.getMessage()),
                renderToolCatalog(agentToolsManager),
                state.renderScratchpad(maxObservationChars),
                StringUtils.isBlank(parseError) ? "" : "The previous response could not be parsed. Fix it and strictly follow the schema. Invalid response: " + trimForPrompt(parseError, 1200)
        );
    }

    public static String buildFinalSystemPrompt(AgentState state) {
        return """
                You now need to deliver the final answer for the user.

                Reference assistant persona and boundaries:
                %s

                Ignore any previous response-format instructions in the reference persona.
                For this final synthesis call, output markdown only.
                Ground the answer in the collected observations and the current question/code context.
                If you identify a bug or bottleneck, point it out clearly before giving suggestions.
                """.formatted(StringUtils.defaultIfBlank(state.getRunContext().getBaseSystemPrompt(), "Be a precise programming assistant."));
    }

    public static String buildFinalUserPrompt(AgentState state, Integer maxObservationChars) {
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
                StringUtils.defaultString(runContext.getQuestion().getTitle(), ""),
                trimForPrompt(runContext.getQuestion().getContent(), 3000),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "unknown"),
                StringUtils.defaultIfBlank(requestBody.getLatestJudgeResult(), ""),
                StringUtils.defaultIfBlank(requestBody.getLanguage(), "text"),
                trimForPrompt(requestBody.getUserCode(), 3000),
                StringUtils.defaultString(requestBody.getMessage()),
                state.renderScratchpad(maxObservationChars)
        );
    }

    public static String trimForPrompt(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return StrUtil.sub(text, 0, maxLength);
    }

    public static String renderToolCatalog(AgentToolsManager agentToolsManager) {
        List<ToolDefinition> toolDefinitions = agentToolsManager.listEnabledTools();
        if (toolDefinitions.isEmpty()) {
            return "- No tools are currently enabled. You should finish directly.";
        }
        List<String> lines = new ArrayList<>();
        for (ToolDefinition definition : toolDefinitions) {
            lines.add("- " + definition.name() + ": " + definition.description()
                    + " | input schema: " + definition.inputSchema());
        }
        return String.join("\n", lines);
    }

}
