package com.baimao.oj.judge.codesangbox.impl;

import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.CodeSandboxProxy;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import com.baimao.oj.model.dto.questionsubmit.JudgeInfo;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;
import com.baimao.oj.model.enums.QuestionSubmitStatusEnum;

import java.util.List;

/**
 * @author baimao
 * @title SampleCodeSandbox
 * 示例代码沙箱：本地调试
 */
public class SampleCodeSandbox implements CodeSandbox {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> input = executeCodeRequest.getInput();
        //todo 执行代码
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(input);
        executeCodeResponse.setMessage("测试成功");
        executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCESS.getText());
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.ACCEPTED.getText());
        judgeInfo.setMemory(100l);
        judgeInfo.setTime(100l);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }
}
