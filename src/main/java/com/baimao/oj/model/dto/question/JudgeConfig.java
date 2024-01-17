package com.baimao.oj.model.dto.question;

import lombok.Data;

/**
 * 题目配置，题目本身的属性（封装成类接收json）
 */
@Data
public class JudgeConfig {

    /**
     * 时间限制（ms）
     */
    private Long timeLimit;

    /**
     * 内存限制（KB)
     */
    private Long memoryLimit;

    /**
     * 堆栈限制（KB)
     */
    private Long stackLimit;
}
