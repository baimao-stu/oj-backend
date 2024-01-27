package com.baimao.oj.judge.strategy;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.dto.question.JudgeConfig;
import com.baimao.oj.model.dto.questionsubmit.JudgeInfo;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;

import java.util.List;

/**
 * @author baimao
 * @title DefaultJudgeStrategy
 * JAVA判题策略 （例如判题时间多给 1s）
 */
public class JavaJudgeStrategy implements JudgeStrategy {

    @Override
    public JudgeInfo doJudge(JudgeContext judgeContext) {

        List<String> inputList = judgeContext.getInputList();
        List<String> outputList = judgeContext.getOutputList();
        Question question = judgeContext.getQuestion();
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        List<JudgeCase> judgeCaseList = judgeContext.getJudgeCaseList();
        JudgeInfo judgeInfo = judgeContext.getJudgeInfo();
        Long needMemory = judgeInfo.getMemory();        //执行此代码花费的内存
        Long needTime = judgeInfo.getTime();            //执行此代码花费的时间

        //默认设置为通过判题AC
        JudgeInfoMessageEnum judgeInfoMessageEnum = JudgeInfoMessageEnum.ACCEPTED;
        JudgeInfo judgeInfoResponse = new JudgeInfo();
        judgeInfoResponse.setMemory(needMemory);
        judgeInfoResponse.setTime(needTime);

        String judgeConfigStr = question.getJudgeConfig();
        // 1. 题目的限制条件
        JudgeConfig judgeConfig = JSONUtil.toBean(judgeConfigStr,JudgeConfig.class);
        Long memoryLimit = judgeConfig.getMemoryLimit();
        Long timeLimit = judgeConfig.getTimeLimit();
        if(needMemory > memoryLimit) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }
        Long JAVA_EXTRACT_TIME_COST = 1000L;
        if(needTime - JAVA_EXTRACT_TIME_COST > timeLimit) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }

        // 2. 得到的输出结果数量不符合预期
        if(outputList.size() != inputList.size()) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.WRONG_ANSWER;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }
        // 3. 依次判断每项输出是否与预期相等
        for (int i = 0; i < judgeCaseList.size(); i++) {
            JudgeCase judgeCase = judgeCaseList.get(i);
            if(! judgeCase.getOutput().equals(outputList.get(i))){
                judgeInfoMessageEnum = JudgeInfoMessageEnum.WRONG_ANSWER;
                judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
                return judgeInfoResponse;
            }
        }

        judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
        return judgeInfoResponse;
    }
}
