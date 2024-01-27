package com.baimao.oj.model.dto.questionsubmit;

import lombok.Data;

/**
 * 判题信息（用户提交题目后的状态）
 */
@Data
public class JudgeInfo {

    /**
     * 判题状态
     */
    private String message;

    /**
     * 用户提交的内存大小
     */
    private Long memory;

    /**
     * 用户的代码花费的时间
     */
    private Long time;

}
