package com.baimao.oj.judge.codesangbox.impl;

import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;

/**
 * @author baimao
 * @title RemoteCodeSandbox
 * 远程代码沙箱
 */
public class RemoteCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("远程代码沙箱");
        return null;
    }
}
