package com.baimao.oj.ai.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 单轮对话请求载荷。
 */
@Data
public class AiChatSendRequest implements Serializable {
    /**
     * OJ 题目编号。
     */
    private Long questionId;
    /**
     * 比赛编号；0 或空值表示非比赛场景。
     */
    private Long contestId;
    /**
     * 聊天模式：普通 或 智能体。
     */
    private String mode;
    /**
     * 用户消息内容。
     */
    private String message;
    /**
     * 用户提交代码语言。
     */
    private String language;
    /**
     * 用于分析与工具调用的用户代码片段。
     */
    private String userCode;
    /**
     * 来自 OJ 的最新判题结果摘要。
     */
    private String latestJudgeResult;
}

