package com.baimao.oj.judge.codesangbox;

import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @author baimao
 * @title CodeSandboxProxy
 * 代码沙箱代理（代理模式，执行代码沙箱时执行额外功能）
 */
@Slf4j
public class CodeSandboxProxy implements CodeSandbox {

    private CodeSandbox codeSandbox;

    public CodeSandboxProxy(CodeSandbox codeSandbox) {
        this.codeSandbox = codeSandbox;
    }

    /**
     * 统一为代码沙箱做功能增强，如打日志（不需要在每个代码沙箱里写打日志的代码）
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("请求代码沙箱：" + executeCodeRequest.toString());
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        log.info("结束代码沙箱：" + executeCodeRequest.toString());
        return executeCodeResponse;
    }
}
