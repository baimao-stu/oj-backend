package com.baimao.oj.judge;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.exception.ThrowUtils;
import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.CodeSandboxFactory;
import com.baimao.oj.judge.codesangbox.CodeSandboxProxy;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import com.baimao.oj.judge.strategy.JudgeContext;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;
import com.baimao.oj.model.enums.QuestionSubmitStatusEnum;
import com.baimao.oj.service.QuestionService;
import com.baimao.oj.service.QuestionSubmitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author baimao
 * @title JudgeServiceImpl
 */
@Service
public class JudgeServiceImpl implements JudgeService {

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeManager judgeManager;

    @Value("${codesandbox.type:sample}")
    private String type;

    @Override
    public QuestionSubmit doJudge(Long questionSubmitId) {
        // 1）根据题目提交 id 获取题目信息、题目提交信息（用户传入的代码和编程语言等）
        QuestionSubmit questionSubmit = questionSubmitService.getById(questionSubmitId);
        if(questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"题目提交信息不存在");
        }

        Long questionId = questionSubmit.getQuestionId();
        Question question = questionService.getById(questionId);
        if(question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"题目信息不存在");
        }

        // 2）如果题目提交状态不为等待中，则不用重复执行
        if(!questionSubmit.getStatus().equals(QuestionSubmitStatusEnum.WAITING.getValue())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"题目正在判题");
        }
        // 3）更改判题（题目提交）的状态为“判题中”，防止重复执行，也可让用户及时得到题目提交的状态“判题中”
        QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.JUDGING.getValue());    //只修改判题状态
        questionSubmitUpdate.setId(questionSubmitId);
        boolean update = questionSubmitService.updateById(questionSubmitUpdate);
        if(!update) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"题目提交状态更新错误");
        }

        /**  4）调用沙箱获得执行结果（输出结果、判题信息） */
        String language = questionSubmit.getLanguage();
        String code = questionSubmit.getCode();
        String judgeCaseStr = question.getJudgeCase();
        List<JudgeCase> judgeCaseList = JSONUtil.toList(judgeCaseStr, JudgeCase.class);
        //获取题目测试输入用例组
        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        /** 创建指定类型的代码沙箱（代理类） */
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code(code)
                .input(inputList)
                .language(language)
                .build();

        /** 执行代码 */
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(request);
        Integer status = executeCodeResponse.getStatus();
        /** 沙箱执行程序过程出错*/
        JudgeInfo judgeInfo = new JudgeInfo();
        if(status == 2) {
            JudgeInfoMessageEnum judgeInfoMessageEnum = JudgeInfoMessageEnum.COMPILE_ERROR;
            judgeInfo.setMessage(judgeInfoMessageEnum.getText());
            judgeInfo.setMemory(0L);
            judgeInfo.setTime(0L);
        }
        else if(status == 3) {
            JudgeInfoMessageEnum judgeInfoMessageEnum = JudgeInfoMessageEnum.RUNTIME_ERROR;
            judgeInfo.setMessage(judgeInfoMessageEnum.getText());
            judgeInfo.setMemory(0L);
            judgeInfo.setTime(0L);
        }else {
            /** 5）执行判断逻辑：根据沙箱执行结果判断结果是否正确，设置题目的判题结果等 */
            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(executeCodeResponse.getOutput());
            judgeContext.setJudgeCaseList(judgeCaseList);
            judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
            judgeContext.setQuestion(question);
            judgeContext.setQuestionSubmit(questionSubmit);
            /** 策略模式：根据不同的编程语言决策执行对应的判题逻辑（比对结果） */
//        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
            judgeInfo = judgeManager.doJudge(judgeContext);
        }

        //修改数据库的判题状态
        questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.SUCCESS.getValue());    //修改判题状态
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));   //修改判题结果
        if(judgeInfo.getErrorIndex() != null) {
            JudgeCase judgeCase = judgeCaseList.get(judgeInfo.getErrorIndex());
            questionSubmitUpdate.setErrorCase(JSONUtil.toJsonStr(judgeCase));
        }

        update = questionSubmitService.updateById(questionSubmitUpdate);
        if(!update) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"题目提交状态更新错误");
        }

        QuestionSubmit questionSubmitResult = questionSubmitService.getById(questionSubmitId);
        return questionSubmitResult;
    }
}
