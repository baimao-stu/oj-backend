package com.baimao.oj.ai.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用于定位用户在题目维度唯一会话的请求对象。
 */
@Data
public class AiChatSessionRequest implements Serializable {
    /**
     * OJ 题目编号。
     */
    private Long questionId;
    /**
     * 比赛编号；0 或空值表示非比赛场景。
     */
    private Long contestId;
}

