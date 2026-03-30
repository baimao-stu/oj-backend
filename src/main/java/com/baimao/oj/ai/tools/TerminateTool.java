package com.baimao.oj.ai.tools;


import org.springframework.ai.tool.annotation.Tool;

/**
 * 终止工具，大模型调用该工具，由agent控制结束任务
 */
public class TerminateTool {

    @Tool(name = "terminateTool",
            description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.\n" +
            "When you have finished all the tasks, call this tool to end the work.")
    public String execute() {
        return "任务结束";
    }

}
