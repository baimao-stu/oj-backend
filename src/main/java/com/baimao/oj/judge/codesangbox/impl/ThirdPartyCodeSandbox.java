package com.baimao.oj.judge.codesangbox.impl;

import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;

/**
 * @author baimao
 * @title ThirdCodeSandbox
 * 第三方代码沙箱
 */
public class ThirdPartyCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("第三方代码沙箱");
        return null;
    }
}