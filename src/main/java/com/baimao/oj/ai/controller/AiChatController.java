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
 * AI 会话初始化、消息发送与 SSE 流式输出接口。
 */
@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    @Resource
    private AiChatService aiChatService;

    /**
     * 获取当前会话上下文与历史消息。
     */
    @PostMapping("/session/get")
    public BaseResponse<AiChatSessionVO> getSession(@RequestBody AiChatSessionRequest aiChatSessionRequest,
                                                    HttpServletRequest request) {
        return ResultUtils.success(aiChatService.getSession(aiChatSessionRequest, request));
    }

    /**
     * 清空当前会话消息。
     */
    @PostMapping("/session/clear")
    public BaseResponse<Boolean> clearSession(@RequestBody AiChatSessionRequest aiChatSessionRequest,
                                              HttpServletRequest request) {
        return ResultUtils.success(aiChatService.clearSession(aiChatSessionRequest, request));
    }

    /**
     * 发送消息并返回完整回复，主要携带的消息（AiChatSendRequest：用户消息、题目ID、语言、代码）
     */
    @PostMapping("/message/send")
    public BaseResponse<AiChatMessageVO> sendMessage(@RequestBody AiChatSendRequest aiChatSendRequest,
                                                     HttpServletRequest request) {
        return ResultUtils.success(aiChatService.chat(aiChatSendRequest, request));
    }

    /**
     * 发送消息并通过 SSE 流式返回回复。
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody AiChatSendRequest aiChatSendRequest,
                                    HttpServletRequest request) {
        return aiChatService.streamChat(aiChatSendRequest, request);
    }
}

