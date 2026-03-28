package com.baimao.oj.ai.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * View object returned to frontend for a single chat message.
 */
@Data
public class AiChatMessageVO implements Serializable {
    /**
     * Message id.
     */
    private Long id;
    /**
     * Role of speaker: user / assistant.
     */
    private String role;
    /**
     * Message mode: normal / agent.
     */
    private String mode;
    /**
     * Plain text/markdown message content.
     */
    private String content;
    /**
     * Tool call trace JSON string in agent mode.
     */
    private String toolCalls;
    /**
     * Message creation time.
     */
    private Date createTime;
}
