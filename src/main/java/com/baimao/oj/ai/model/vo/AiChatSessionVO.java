package com.baimao.oj.ai.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * View object returned to frontend for chat session bootstrap.
 */
@Data
public class AiChatSessionVO implements Serializable {
    /**
     * Session id.
     */
    private Long sessionId;
    /**
     * Session status enum value.
     */
    private Integer status;
    /**
     * Current session mode.
     */
    private String mode;
    /**
     * Whether AI is currently enabled for this scope.
     */
    private Boolean enabled;
    /**
     * Disable reason if disabled.
     */
    private String disableReason;
    /**
     * Ordered message history.
     */
    private List<AiChatMessageVO> messageList;
}
