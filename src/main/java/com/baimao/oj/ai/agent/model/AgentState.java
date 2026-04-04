package com.baimao.oj.ai.agent.model;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 当前步骤下 Agent的状态
 */
public class AgentState {

    private final AgentRunContext runContext;

    // 每一步的轨迹列表（thought/action/observation）
    private final List<AgentStepTrace> stepTraces = new ArrayList<>();

    // 当前 step 的决策结果（思考结果），供 act / finish 使用
    private AgentDecision currentDecision;

    private boolean finished;

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

    public AgentDecision getCurrentDecision() {
        return currentDecision;
    }

    public void setCurrentDecision(AgentDecision currentDecision) {
        this.currentDecision = currentDecision;
    }

    public boolean isFinished() {
        return finished;
    }

    public void markFinished() {
        this.finished = true;
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
     * 把历史轨迹（思维链）拼成结构化文本（Step/Thought/Plan/Action/Observation），
     * 并按 maxObservationChars 截断 Observation，避免 prompt 过大。
     * @param maxObservationChars
     * @return
     */
    public String renderScratchpad(int maxObservationChars) {
        if (stepTraces.isEmpty()) {
            return "None";
        }
        String result = stepTraces.stream()
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
                        if (observation.length() > maxObservationChars) {
                            observation = observation.substring(0, maxObservationChars) + "...";
                        }
                        sb.append("Observation: ").append(observation).append('\n');
                    }
                    return sb.toString().trim();
                })
                .collect(Collectors.joining("\n\n"));
        return result;
    }
}
