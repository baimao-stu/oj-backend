package com.baimao.oj.ai.service;

import cn.hutool.core.collection.CollUtil;
import com.baimao.oj.ai.config.AiProperties;
import com.baimao.oj.ai.mapper.AiChatMessageMapper;
import com.baimao.oj.ai.model.entity.AiChatMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
/**
 * 基于项目现有 `ai_chat_message` 表实现的 Spring AI `ChatMemory` 适配器。
 * 这样可以继续复用原有消息存储结构，同时把历史上下文注入交给 Spring AI 处理。
 */
public class AiDatabaseChatMemory implements ChatMemory {

    @Resource
    private AiChatMessageMapper aiChatMessageMapper;

    @Resource
    private AiProperties aiProperties;

    @Override
    /**
     * 这里不直接接管写入逻辑。
     * 当前项目仍由业务服务层负责消息落库，避免和既有表结构、字段语义冲突。
     */
    public void add(String conversationId, List<Message> messages) {
        // Message persistence is still handled by the existing service layer
        // so that DB schema and frontend payload stay compatible.
    }

    @Override
    /**
     * 从数据库中读取会话历史，并转换成 Spring AI 可识别的消息列表。
     */
    public List<Message> get(String conversationId) {
        Long sessionId = parseConversationId(conversationId);
        if (sessionId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AiChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatMessage::getSessionId, sessionId);
        queryWrapper.orderByDesc(AiChatMessage::getId);
        queryWrapper.last("limit " + Math.max(aiProperties.getMaxHistoryMessages(), 1));
        List<AiChatMessage> messages = aiChatMessageMapper.selectList(queryWrapper);
        if (CollUtil.isEmpty(messages)) {
            return Collections.emptyList();
        }
        messages.sort((left, right) -> left.getId().compareTo(right.getId()));
        return messages.stream()
                .map(this::toSpringAiMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    /**
     * 清空指定会话在数据库中的消息。
     */
    public void clear(String conversationId) {
        Long sessionId = parseConversationId(conversationId);
        if (sessionId == null) {
            return;
        }
        LambdaQueryWrapper<AiChatMessage> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(AiChatMessage::getSessionId, sessionId);
        aiChatMessageMapper.delete(deleteWrapper);
    }

    /**
     * 将数据库消息转换为 Spring AI `Message`。
     */
    private Message toSpringAiMessage(AiChatMessage dbMessage) {
        if (dbMessage == null || StringUtils.isBlank(dbMessage.getContent())) {
            return null;
        }
        if (Constant.ROLE_ASSISTANT.equalsIgnoreCase(dbMessage.getRole())) {
            return new AssistantMessage(dbMessage.getContent());
        }
        return new UserMessage(dbMessage.getContent());
    }

    /**
     * Spring AI 侧的 `conversationId` 在本项目里实际就是 `sessionId`。
     */
    private Long parseConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return null;
        }
        try {
            return Long.valueOf(conversationId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
