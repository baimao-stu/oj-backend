package com.baimao.oj.ai.agent.tools;

/**
 * 工具统一执行函数接口，便于在公共流程中复用限流和事件逻辑。
 */
@FunctionalInterface
public interface ToolExecutor {

    String execute(AgentToolsManager.RuntimeContext runtimeContext);

}