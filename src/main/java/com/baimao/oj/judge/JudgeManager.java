package com.baimao.oj.judge;

import com.baimao.oj.judge.strategy.DefaultJudgeStrategy;
import com.baimao.oj.judge.strategy.JavaJudgeStrategy;
import com.baimao.oj.judge.strategy.JudgeContext;
import com.baimao.oj.judge.strategy.JudgeStrategy;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import org.springframework.stereotype.Service;

/**
 * @author baimao
 * @title JudgeManager
 * 判题管理器（策略模式）：统一管理判题所采用的策略
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext){
        String language = judgeContext.getQuestionSubmit().getLanguage();
        JudgeInfo judgeInfo = null;
        switch (language) {
            case "java":
                JudgeStrategy javaJudgeStrategy = new JavaJudgeStrategy();
                judgeInfo = javaJudgeStrategy.doJudge(judgeContext);
                return judgeInfo;
            default:
                JudgeStrategy defaultJudgeStrategy = new DefaultJudgeStrategy();
                judgeInfo = defaultJudgeStrategy.doJudge(judgeContext);
                return judgeInfo;
        }
    }
}
