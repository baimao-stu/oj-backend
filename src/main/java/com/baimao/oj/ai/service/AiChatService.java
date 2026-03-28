package com.baimao.oj.ai.service;

import com.baimao.oj.ai.dto.AiChatSendRequest;
import com.baimao.oj.ai.dto.AiChatSessionRequest;
import com.baimao.oj.ai.model.AiChatMessageVO;
import com.baimao.oj.ai.model.AiChatSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AI chat domain service for session management and message generation.
 */
public interface AiChatService {

    /**
     * Load or create a session and return history/messages for UI bootstrap.
     */
    AiChatSessionVO getSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request);

    /**
     * Clear messages in current scoped session.
     */
    Boolean clearSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request);

    /**
     * Send one message in non-streaming mode.
     */
    AiChatMessageVO chat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request);

    /**
     * Send one message in streaming mode with SSE events.
     */
    SseEmitter streamChat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request);

    /**
     * Archive expired sessions according to retention policy.
     */
    void archiveExpiredSessions();
}
