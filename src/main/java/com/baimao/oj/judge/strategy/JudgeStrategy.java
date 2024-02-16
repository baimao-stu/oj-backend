package com.baimao.oj.judge.strategy;

import com.baimao.oj.judge.codesangbox.model.JudgeInfo;

/**
 * @author baimao
 * @title JudgeStrategy
 * 判题策略（比对输出、比对判题条件：时间、内存）
 */
public interface JudgeStrategy {

    /**
     * 执行判题
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext);

}
