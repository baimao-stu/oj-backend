package com.baimao.oj.ai.controller;

import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.dto.AiChatSessionRequest;
import com.baimao.oj.ai.model.vo.AiChatMessageVO;
import com.baimao.oj.ai.model.vo.AiChatSessionVO;
import com.baimao.oj.ai.service.AiChatService;
import com.baimao.oj.common.BaseResponse;
import com.baimao.oj.common.ResultUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP endpoints for AI session bootstrap, chat send, and SSE streaming.
 */
@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    @Resource
    private AiChatService aiChatService;

    /**
     * Get current session context and message history.
     */
    @PostMapping("/session/get")
    public BaseResponse<AiChatSessionVO> getSession(@RequestBody AiChatSessionRequest aiChatSessionRequest,
                                                    HttpServletRequest request) {
        return ResultUtils.success(aiChatService.getSession(aiChatSessionRequest, request));
    }

    /**
     * Clear current session messages.
     */
    @PostMapping("/session/clear")
    public BaseResponse<Boolean> clearSession(@RequestBody AiChatSessionRequest aiChatSessionRequest,
                                              HttpServletRequest request) {
        return ResultUtils.success(aiChatService.clearSession(aiChatSessionRequest, request));
    }

    /**
     * Send a message and return the full assistant response.
     */
    @PostMapping("/message/send")
    public BaseResponse<AiChatMessageVO> sendMessage(@RequestBody AiChatSendRequest aiChatSendRequest,
                                                     HttpServletRequest request) {
        return ResultUtils.success(aiChatService.chat(aiChatSendRequest, request));
    }

    /**
     * Send a message and stream assistant response with SSE.
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody AiChatSendRequest aiChatSendRequest,
                                    HttpServletRequest request) {
        return aiChatService.streamChat(aiChatSendRequest, request);
    }
}
