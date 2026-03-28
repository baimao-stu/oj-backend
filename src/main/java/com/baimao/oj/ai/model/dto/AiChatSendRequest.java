package com.baimao.oj.ai.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Request payload for one chat turn.
 */
@Data
public class AiChatSendRequest implements Serializable {
    /**
     * Question id in OJ.
     */
    private Long questionId;
    /**
     * Contest id; 0 or null means non-contest scenario.
     */
    private Long contestId;
    /**
     * Chat mode: normal or agent.
     */
    private String mode;
    /**
     * User message content.
     */
    private String message;
    /**
     * Source code language of the user submission.
     */
    private String language;
    /**
     * User code snippet used for analysis and tool calls.
     */
    private String userCode;
    /**
     * Latest judge result summary from OJ.
     */
    private String latestJudgeResult;
}
