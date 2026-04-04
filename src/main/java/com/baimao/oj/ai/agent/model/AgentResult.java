package com.baimao.oj.ai.agent.model;

/**
 * Agent 运行结束后的聚合结果。
 *
 * @param assistantContent 传给对话链路的助手完整消息
 * @param finalAnswer 面向用户展示的最终答复
 */
public record AgentResult(String assistantContent, String finalAnswer) {
}
