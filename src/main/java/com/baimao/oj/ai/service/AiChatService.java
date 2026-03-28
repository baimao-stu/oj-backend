package com.baimao.oj.ai.service;

import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.dto.AiChatSessionRequest;
import com.baimao.oj.ai.model.vo.AiChatMessageVO;
import com.baimao.oj.ai.model.vo.AiChatSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AI 聊天领域服务，负责会话管理与消息生成。
 */
public interface AiChatService {

    /**
     * 加载或创建会话，并返回前端初始化所需历史消息。
     */
    AiChatSessionVO getSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request);

    /**
     * 清空当前作用域会话消息。
     */
    Boolean clearSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request);

    /**
     * 以非流式方式发送一条消息。
     */
    AiChatMessageVO chat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request);

    /**
     * 以 SSE 事件流方式发送一条消息。
     */
    SseEmitter streamChat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request);

    /**
     * 按保留策略归档过期会话。
     */
    void archiveExpiredSessions();
}

