package com.baimao.oj.ai.agent.model;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 一次运行过程中的可变状态。
 */
public class AgentState {

    private final AgentRunContext runContext;

    private final List<AgentStepTrace> stepTraces = new ArrayList<>();

    private int currentStep;

    public AgentState(AgentRunContext runContext) {
        this.runContext = runContext;
    }

    public AgentRunContext getRunContext() {
        return runContext;
    }

    public List<AgentStepTrace> getStepTraces() {
        return stepTraces;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void nextStep() {
        this.currentStep += 1;
    }

    public void addTrace(AgentStepTrace trace) {
        if (trace != null) {
            this.stepTraces.add(trace);
        }
    }

    /**
     * 将历史步骤渲染成给模型看的 scratchpad 文本。
     */
    public String renderScratchpad(int maxObservationChars) {
        if (stepTraces.isEmpty()) {
            return "None";
        }
        return stepTraces.stream()
                .map(trace -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Step ").append(trace.getStepNo()).append('\n');
                    if (StringUtils.isNotBlank(trace.getThought())) {
                        sb.append("Thought: ").append(trace.getThought()).append('\n');
                    }
                    if (trace.getPlan() != null && !trace.getPlan().isEmpty()) {
                        sb.append("Plan: ").append(String.join(" | ", trace.getPlan())).append('\n');
                    }
                    sb.append("Action: ").append(trace.getActionType());
                    if (StringUtils.isNotBlank(trace.getToolName())) {
                        sb.append(" -> ").append(trace.getToolName());
                    }
                    if (StringUtils.isNotBlank(trace.getToolInputJson())) {
                        sb.append(" ").append(trace.getToolInputJson());
                    }
                    sb.append('\n');
                    if (StringUtils.isNotBlank(trace.getObservation())) {
                        String observation = trace.getObservation();
                        // 观察结果可能很长，这里做截断以控制提示词体积。
                        if (observation.length() > maxObservationChars) {
                            observation = observation.substring(0, maxObservationChars) + "...";
                        }
                        sb.append("Observation: ").append(observation).append('\n');
                    }
                    return sb.toString().trim();
                })
                .collect(Collectors.joining("\n\n"));
    }
}
