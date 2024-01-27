package com.baimao.oj.judge.codesangbox;

import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;

/**
 * @author baimao
 * @title CodeSandbox
 * 代码沙箱接口
 */
public interface CodeSandbox {

    /**
     * 执行代码
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
