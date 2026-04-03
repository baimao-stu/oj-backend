package com.baimao.oj.ai.agent.model;

/**
 * Agent 运行结束后的聚合结果。
 *
 * @param assistantContent 带有 analysis/final 标记的完整消息
 * @param finalAnswer 面向用户展示的最终答复
 * @param reasoningSummary 执行过程摘要
 */
public record AgentResult(String assistantContent, String finalAnswer, String reasoningSummary) {
}
