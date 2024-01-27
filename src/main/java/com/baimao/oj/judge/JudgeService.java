package com.baimao.oj.judge;

import com.baimao.oj.model.entity.QuestionSubmit;

/**
 * @author baimao
 * @title JudgeService
 * 判题服务（调用沙箱、根据策略判题）
 */
public interface JudgeService {

    /**
     * 判题
     * @param questionSubmitId 提交id
     * @return
     */
    QuestionSubmit doJudge(Long questionSubmitId);
}
