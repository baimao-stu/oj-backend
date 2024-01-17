package com.baimao.oj.model.dto.question;

import lombok.Data;

/**
 * 题目用例（每道题目都有输入输出样例，校验用户的代码）
 */
@Data
public class JudgeCase {

    /**
     * 输入用例
     */
    private String input;

    /**
     * 输出用例
     */
    private String output;


}
