package com.baimao.oj.judge.strategy;

import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.dto.questionsubmit.JudgeInfo;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import lombok.Data;

import java.util.List;

/**
 * @author baimao
 * @title JudgeContext
 * 上下文：包含策略判题中各种可能需要的参数
 */
@Data
public class JudgeContext {

    /**
     * 题目的测试输入用例（也可以在下面的judgeCaseList中获取）
     */
    private List<String> inputList;

    /**
     * 沙箱执行后的输出结果
     */
    private List<String> outputList;

    /**
     * 题目的测试用例（也可以在下面的question中获取）
     */
    private List<JudgeCase> judgeCaseList;

    /**
     * 题目的判题信息
     */
    private JudgeInfo judgeInfo;

    /**
     * 执行的题目
     */
    private Question question;

    /**
     * 题目的提交信息
     */
    private QuestionSubmit questionSubmit;
}
