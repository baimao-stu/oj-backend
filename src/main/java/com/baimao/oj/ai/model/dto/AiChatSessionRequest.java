package com.baimao.oj.ai.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Request used to locate a unique AI chat session for a user on a question.
 */
@Data
public class AiChatSessionRequest implements Serializable {
    /**
     * Question id in OJ.
     */
    private Long questionId;
    /**
     * Contest id; 0 or null means non-contest scenario.
     */
    private Long contestId;
}
